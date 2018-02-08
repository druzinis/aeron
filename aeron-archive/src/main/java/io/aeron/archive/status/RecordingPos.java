/*
 * Copyright 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive.status;

import io.aeron.Aeron;
import io.aeron.Counter;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;

import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.agrona.concurrent.status.CountersReader.*;

/**
 * The position a recording has reached when being archived.
 * <p>
 * Key has the following layout:
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                        Recording ID                           |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                     Control Session ID                        |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                       Correlation ID                          |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                         Session ID                            |
 *  +---------------------------------------------------------------+
 *  |                         Stream ID                             |
 *  +---------------------------------------------------------------+
 * </pre>
 */
public class RecordingPos
{
    /**
     * Type id of a recording position counter.
     */
    public static final int RECORDING_POSITION_TYPE_ID = 100;

    /**
     * Represents a null recording id when not found.
     */
    public static final long NULL_RECORDING_ID = -1L;

    /**
     * Human readable name for the counter.
     */
    public static final String NAME = "rec-pos";

    public static final int RECORDING_ID_OFFSET = 0;
    public static final int CONTROL_SESSION_ID_OFFSET = RECORDING_ID_OFFSET + SIZE_OF_LONG;
    public static final int CORRELATION_ID_OFFSET = CONTROL_SESSION_ID_OFFSET + SIZE_OF_LONG;
    public static final int SESSION_ID_OFFSET = CORRELATION_ID_OFFSET + SIZE_OF_LONG;
    public static final int STREAM_ID_OFFSET = SESSION_ID_OFFSET + SIZE_OF_INT;
    public static final int KEY_LENGTH = STREAM_ID_OFFSET + SIZE_OF_INT;

    public static Counter allocate(
        final Aeron aeron,
        final UnsafeBuffer tempBuffer,
        final long recordingId,
        final long controlSessionId,
        final long correlationId,
        final int sessionId,
        final int streamId,
        final String strippedChannel)
    {
        tempBuffer.putLong(RECORDING_ID_OFFSET, recordingId);
        tempBuffer.putLong(CONTROL_SESSION_ID_OFFSET, controlSessionId);
        tempBuffer.putLong(CORRELATION_ID_OFFSET, correlationId);
        tempBuffer.putInt(SESSION_ID_OFFSET, sessionId);
        tempBuffer.putInt(STREAM_ID_OFFSET, streamId);

        int labelLength = 0;
        labelLength += tempBuffer.putStringWithoutLengthAscii(KEY_LENGTH, NAME + ": ");
        labelLength += tempBuffer.putLongAscii(KEY_LENGTH + labelLength, recordingId);
        labelLength += tempBuffer.putStringWithoutLengthAscii(KEY_LENGTH + labelLength, " ");
        labelLength += tempBuffer.putIntAscii(KEY_LENGTH + labelLength, sessionId);
        labelLength += tempBuffer.putStringWithoutLengthAscii(KEY_LENGTH + labelLength, " ");
        labelLength += tempBuffer.putIntAscii(KEY_LENGTH + labelLength, streamId);
        labelLength += tempBuffer.putStringWithoutLengthAscii(KEY_LENGTH + labelLength, " ");
        labelLength += tempBuffer.putStringWithoutLengthAscii(
            KEY_LENGTH + labelLength, strippedChannel, 0, MAX_LABEL_LENGTH - labelLength);

        return aeron.addCounter(
            RECORDING_POSITION_TYPE_ID,
            tempBuffer,
            0,
            KEY_LENGTH,
            tempBuffer,
            KEY_LENGTH,
            labelLength);
    }

    /**
     * Find the active counter id for a stream based on the recording id.
     *
     * @param countersReader to search within.
     * @param recordingId    for the active recording.
     * @return the counter id if found otherwise {@link CountersReader#NULL_COUNTER_ID}.
     */
    public static int findCounterIdByRecording(final CountersReader countersReader, final long recordingId)
    {
        final DirectBuffer buffer = countersReader.metaDataBuffer();

        for (int i = 0, size = countersReader.maxCounterId(); i < size; i++)
        {
            if (countersReader.getCounterState(i) == RECORD_ALLOCATED)
            {
                final int recordOffset = CountersReader.metaDataOffset(i);

                if (buffer.getInt(recordOffset + TYPE_ID_OFFSET) == RECORDING_POSITION_TYPE_ID &&
                    buffer.getLong(recordOffset + KEY_OFFSET + RECORDING_ID_OFFSET) == recordingId)
                {
                    return i;
                }
            }
        }

        return NULL_COUNTER_ID;
    }

    /**
     * Count the number of counters for a given session. It is possible for different recording to exist on the
     * same session if there are images under subscriptions with different channel and stream id.
     *
     * @param countersReader to search within.
     * @param sessionId      to search for.
     * @return the count of recordings matching a session id.
     */
    public static int countBySession(final CountersReader countersReader, final int sessionId)
    {
        int count = 0;
        final DirectBuffer buffer = countersReader.metaDataBuffer();

        for (int i = 0, size = countersReader.maxCounterId(); i < size; i++)
        {
            if (countersReader.getCounterState(i) == RECORD_ALLOCATED)
            {
                final int recordOffset = CountersReader.metaDataOffset(i);

                if (buffer.getInt(recordOffset + TYPE_ID_OFFSET) == RECORDING_POSITION_TYPE_ID &&
                    buffer.getInt(recordOffset + KEY_OFFSET + SESSION_ID_OFFSET) == sessionId)
                {
                    ++count;
                }
            }
        }

        return count;
    }

    /**
     * Find the active counter id for a stream based on the session id.
     *
     * @param countersReader to search within.
     * @param sessionId      for the active recording.
     * @return the counter id if found otherwise {@link CountersReader#NULL_COUNTER_ID}.
     */
    public static int findCounterIdBySession(final CountersReader countersReader, final int sessionId)
    {
        final DirectBuffer buffer = countersReader.metaDataBuffer();

        for (int i = 0, size = countersReader.maxCounterId(); i < size; i++)
        {
            if (countersReader.getCounterState(i) == RECORD_ALLOCATED)
            {
                final int recordOffset = CountersReader.metaDataOffset(i);

                if (buffer.getInt(recordOffset + TYPE_ID_OFFSET) == RECORDING_POSITION_TYPE_ID &&
                    buffer.getInt(recordOffset + KEY_OFFSET + SESSION_ID_OFFSET) == sessionId)
                {
                    return i;
                }
            }
        }

        return NULL_COUNTER_ID;
    }

    /**
     * Get the recording id for a given counter id.
     *
     * @param countersReader to search within.
     * @param counterId      for the active recording.
     * @return the counter id if found otherwise {@link #NULL_RECORDING_ID}.
     */
    public static long getRecordingId(final CountersReader countersReader, final int counterId)
    {
        final DirectBuffer buffer = countersReader.metaDataBuffer();

        if (countersReader.getCounterState(counterId) == RECORD_ALLOCATED)
        {
            final int recordOffset = CountersReader.metaDataOffset(counterId);

            if (buffer.getInt(recordOffset + TYPE_ID_OFFSET) == RECORDING_POSITION_TYPE_ID)
            {
                return buffer.getLong(recordOffset + KEY_OFFSET + RECORDING_ID_OFFSET);
            }
        }

        return NULL_RECORDING_ID;
    }

    /**
     * Is the recording counter still active.
     *
     * @param countersReader to search within.
     * @param counterId      to search for.
     * @param recordingId    to confirm it is still the same value.
     * @return true if the counter is still active otherwise false.
     */
    public static boolean isActive(final CountersReader countersReader, final int counterId, final long recordingId)
    {
        final DirectBuffer buffer = countersReader.metaDataBuffer();

        if (countersReader.getCounterState(counterId) == RECORD_ALLOCATED)
        {
            final int recordOffset = CountersReader.metaDataOffset(counterId);

            return
                buffer.getInt(recordOffset + TYPE_ID_OFFSET) == RECORDING_POSITION_TYPE_ID &&
                buffer.getLong(recordOffset + KEY_OFFSET + RECORDING_ID_OFFSET) == recordingId;
        }

        return false;
    }
}
