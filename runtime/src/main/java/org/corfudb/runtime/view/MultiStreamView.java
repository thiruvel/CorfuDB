package org.corfudb.runtime.view;

import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.logprotocol.IDivisibleEntry;
import org.corfudb.protocols.logprotocol.StreamCOWEntry;
import org.corfudb.protocols.wireprotocol.*;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.exceptions.AbortCause;
import org.corfudb.runtime.exceptions.OverwriteException;
import org.corfudb.runtime.exceptions.ReplexOverwriteException;
import org.corfudb.runtime.exceptions.TransactionAbortedException;
import org.corfudb.runtime.view.stream.BackpointerStreamView;
import org.corfudb.runtime.view.stream.IStreamView;
import org.corfudb.util.Utils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by mwei on 12/11/15.
 */
@Slf4j
public class MultiStreamView {

    /**
     * The org.corfudb.runtime which backs this view.
     */
    CorfuRuntime runtime;

    public MultiStreamView(CorfuRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Get a view on a stream. The view has its own pointer to the stream.
     *
     * @param stream The UUID of the stream to get a view on.
     * @return A view
     */
    public IStreamView get(UUID stream) {
        return getReplicationMode().getStreamView(runtime, stream);
    }

    private Layout.ReplicationMode getReplicationMode() {
        return runtime.getLayoutView().getLayout().getSegments().get(
                runtime.getLayoutView().getLayout().getSegments().size() - 1)
                .getReplicationMode();
    }

    /**
     * Make a copy-on-append copy of a stream.
     *
     * @param source      The UUID of the stream to make a copy of.
     * @param destination The UUID of the destination stream. It must not exist.
     * @return A view
     */
    public IStreamView copy(UUID source, UUID destination, long timestamp) {
        boolean written = false;
        while (!written) {
            TokenResponse tokenResponse =
                    runtime.getSequencerView().nextToken(Collections.singleton(destination), 1);
            if (tokenResponse.getBackpointerMap().get(destination) != null &&
                    !tokenResponse.getBackpointerMap().get(destination).equals(-1L)) {
                try {
                    runtime.getAddressSpaceView().fillHole(tokenResponse.getToken().getTokenValue());
                } catch (OverwriteException oe) {
                    log.trace("Attempted to hole fill due to already-existing stream but hole filled by other party");
                }
                throw new RuntimeException("Stream already exists!");
            }
            StreamCOWEntry entry = new StreamCOWEntry(source, timestamp);
            try {
                runtime.getAddressSpaceView().write(tokenResponse, entry);
                written = true;
            } catch (OverwriteException oe) {
                log.debug("hole fill during COW entry append, retrying...");
            }
        }
        return new BackpointerStreamView(runtime, destination);
    }

    /** Append to multiple streams simultaneously, possibly providing
     * information on how to resolve conflicts.
     * @param streamIDs     The streams to append to.
     * @param object        The object to append to each stream.
     * @param conflictInfo  Conflict information for the sequencer to check.
     * @return              The address the entry was written to.
     * @throws TransactionAbortedException      If the transaction was aborted by
     *                                          the sequencer.
     */
    public long append(@Nonnull Set<UUID> streamIDs, @Nonnull Object object,
                       @Nullable TxResolutionInfo conflictInfo)
        throws TransactionAbortedException {

        // Go to the sequencer, grab an initial token.
        TokenResponse tokenResponse = conflictInfo == null ?
                // Token w/o conflict info
                runtime.getSequencerView().nextToken(streamIDs, 1) :
                // Token w/ conflict info
                runtime.getSequencerView().nextToken(streamIDs, 1, conflictInfo);

        // Until we've succeeded
        while (true) {

            // Is our token a valid type?
            if (tokenResponse.getRespType() == TokenType.TX_ABORT_CONFLICT)
                throw new TransactionAbortedException(
                        tokenResponse.getToken().getTokenValue(),
                        AbortCause.CONFLICT
                );

            if (tokenResponse.getRespType() == TokenType.TX_ABORT_NEWSEQ)
                throw new TransactionAbortedException(
                        -1L,
                        AbortCause.NEW_SEQUENCER
                );

            // Attempt to write to the log
            try {
                runtime.getAddressSpaceView().write(tokenResponse, object);
                // If we're here, we succeeded, return the acquired token
                return tokenResponse.getTokenValue();
            } catch (OverwriteException oe) {
                log.trace("Overwrite[{}]: streams {}", tokenResponse.getTokenValue(),
                        streamIDs.stream().map(Utils::toReadableID).collect(Collectors.toSet()));
                // On retry, check for conflicts only from the previous
                // attempt position
                conflictInfo.setSnapshotTimestamp(tokenResponse.getToken().getTokenValue());

                // We were overwritten, get a new token and try again.
                TokenResponse temp = conflictInfo == null ?
                        // Token w/o conflict info
                        runtime.getSequencerView().nextToken(streamIDs, 1) :
                        // Token w/ conflict info
                        runtime.getSequencerView().nextToken(streamIDs, 1, conflictInfo);

                // We need to fix the token (to use the stream addresses- may
                // eventually be deprecated since these are no longer used)
                tokenResponse = new TokenResponse(temp.getRespType(), temp.getToken(),
                        temp.getBackpointerMap(), tokenResponse.getStreamAddresses());
            }
        }

    }


}