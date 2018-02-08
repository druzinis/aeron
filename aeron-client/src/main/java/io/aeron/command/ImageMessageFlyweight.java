/*
 * Copyright 2014-2017 Real Logic Ltd.
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
package io.aeron.command;

import org.agrona.MutableDirectBuffer;

import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;

/**
 * Control message flyweight for any message that needs to represent a connection
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                        Correlation ID                         |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                 Subscription Registration ID                  |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                          Stream ID                            |
 *  +---------------------------------------------------------------+
 *  |                       Channel Length                          |
 *  +---------------------------------------------------------------+
 *  |                       Channel (ASCII)                        ...
 * ...                                                              |
 *  +---------------------------------------------------------------+
 * </pre>
 */
public class ImageMessageFlyweight
{
    private static final int CORRELATION_ID_OFFSET = 0;
    private static final int SUBSCRIPTION_REGISTRATION_ID_OFFSET = CORRELATION_ID_OFFSET + SIZE_OF_LONG;
    private static final int STREAM_ID_FIELD_OFFSET = SUBSCRIPTION_REGISTRATION_ID_OFFSET + SIZE_OF_LONG;
    private static final int CHANNEL_OFFSET = STREAM_ID_FIELD_OFFSET + SIZE_OF_INT;

    private MutableDirectBuffer buffer;
    private int offset;
    private int lengthOfChannel;

    /**
     * Wrap the buffer at a given offset for updates.
     *
     * @param buffer to wrap
     * @param offset at which the message begins.
     * @return for fluent API
     */
    public final ImageMessageFlyweight wrap(final MutableDirectBuffer buffer, final int offset)
    {
        this.buffer = buffer;
        this.offset = offset;

        return this;
    }

    /**
     * return correlation id field
     *
     * @return correlation id field
     */
    public long correlationId()
    {
        return buffer.getLong(offset + CORRELATION_ID_OFFSET);
    }

    /**
     * set correlation id field
     *
     * @param correlationId field value
     * @return flyweight
     */
    public ImageMessageFlyweight correlationId(final long correlationId)
    {
        buffer.putLong(offset + CORRELATION_ID_OFFSET, correlationId);

        return this;
    }

    /**
     * Registration ID for the subscription.
     *
     * @return registration ID for the subscription.
     */
    public long subscriptionRegistrationId()
    {
        return buffer.getLong(offset + SUBSCRIPTION_REGISTRATION_ID_OFFSET);
    }

    /**
     * Set the tegistration ID for the subscription.
     *
     * @param registrationId for the subscription
     * @return flyweight
     */
    public ImageMessageFlyweight subscriptionRegistrationId(final long registrationId)
    {
        buffer.putLong(offset + SUBSCRIPTION_REGISTRATION_ID_OFFSET, registrationId);

        return this;
    }

    /**
     * return stream id field
     *
     * @return stream id field
     */
    public int streamId()
    {
        return buffer.getInt(offset + STREAM_ID_FIELD_OFFSET);
    }

    /**
     * set stream id field
     *
     * @param streamId field value
     * @return flyweight
     */
    public ImageMessageFlyweight streamId(final int streamId)
    {
        buffer.putInt(offset + STREAM_ID_FIELD_OFFSET, streamId);

        return this;
    }

    /**
     * Get the channel field as ASCII
     *
     * @return channel field
     */
    public String channel()
    {
        final int length = buffer.getInt(offset + CHANNEL_OFFSET);
        lengthOfChannel = SIZE_OF_INT + length;

        return buffer.getStringAscii(offset + CHANNEL_OFFSET, length);
    }

    /**
     * Set the channel field as ASCII
     *
     * @param channel field value
     * @return flyweight
     */
    public ImageMessageFlyweight channel(final String channel)
    {
        lengthOfChannel = buffer.putStringAscii(offset + CHANNEL_OFFSET, channel);

        return this;
    }

    /**
     * Get the length of the current message
     * <p>
     * NB: must be called after the data is written in order to be accurate.
     *
     * @return the length of the current message
     */
    public int length()
    {
        return CHANNEL_OFFSET + lengthOfChannel;
    }
}
