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
package io.aeron.cluster.client;

import io.aeron.*;
import io.aeron.cluster.AuthenticationException;
import io.aeron.cluster.codecs.*;
import io.aeron.exceptions.TimeoutException;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.CloseHelper;
import org.agrona.concurrent.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.agrona.SystemUtil.getDurationInNanos;

/**
 * Client for interacting with an Aeron Cluster.
 * <p>
 * A client will connect to open a session and then offer ingress messages which are replicated to clustered service
 * for reliability. If the clustered service responds then these response messages and events come back via the egress
 * stream.
 */
public final class AeronCluster implements AutoCloseable
{
    private static final int SEND_ATTEMPTS = 3;
    private static final int FRAGMENT_LIMIT = 1;

    private final long clusterSessionId;
    private final boolean isUnicast;
    private final Context ctx;
    private final Aeron aeron;
    private final Subscription subscription;
    private final Publication publication;
    private final NanoClock nanoClock;
    private final Lock lock;
    private final IdleStrategy idleStrategy;
    private final BufferClaim bufferClaim = new BufferClaim();
    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final SessionKeepAliveRequestEncoder keepAliveRequestEncoder = new SessionKeepAliveRequestEncoder();

    /**
     * Connect to the cluster using default configuration.
     *
     * @return allocated cluster client if the connection is successful.
     */
    public static AeronCluster connect()
    {
        return connect(new Context());
    }

    /**
     * Connect to the cluster providing {@link Context} for configuration.
     *
     * @param ctx for configuration.
     * @return allocated cluster client if the connection is successful.
     */
    public static AeronCluster connect(final AeronCluster.Context ctx)
    {
        return new AeronCluster(ctx);
    }

    private AeronCluster(final Context ctx)
    {
        this.ctx = ctx;

        Subscription subscription = null;
        Publication publication = null;

        try
        {
            ctx.conclude();

            this.aeron = ctx.aeron();
            this.lock = ctx.lock();
            this.idleStrategy = ctx.idleStrategy();
            this.nanoClock = aeron.context().nanoClock();
            this.isUnicast = ctx.clusterMemberEndpoints() != null;

            publication = connectToCluster();
            this.publication = publication;

            subscription = aeron.addSubscription(ctx.egressChannel(), ctx.egressStreamId());
            this.subscription = subscription;

            this.clusterSessionId = openSession();
        }
        catch (final Exception ex)
        {
            if (!ctx.ownsAeronClient())
            {
                CloseHelper.quietClose(publication);
                CloseHelper.quietClose(subscription);
            }

            CloseHelper.quietClose(ctx);
            throw ex;
        }
    }

    /**
     * Close session and release associated resources.
     */
    public void close()
    {
        lock.lock();
        try
        {
            if (publication.isConnected())
            {
                closeSession();
            }

            if (!ctx.ownsAeronClient())
            {
                CloseHelper.close(subscription);
                CloseHelper.close(publication);
            }

            ctx.close();
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Get the context used to launch this cluster client.
     *
     * @return the context used to launch this cluster client.
     */
    public Context context()
    {
        return ctx;
    }

    /**
     * Cluster session id for the session that was opened as the result of a successful connect.
     *
     * @return session id for the session that was opened as the result of a successful connect.
     */
    public long clusterSessionId()
    {
        return clusterSessionId;
    }

    /**
     * Get the raw {@link Publication} for sending to the cluster.
     * <p>
     * This can be wrapped with a {@link SessionDecorator} for pre-pending the cluster session header to messages.
     * {@link io.aeron.cluster.codecs.SessionHeaderEncoder} or equivalent should be used to raw access.
     *
     * @return the raw {@link Publication} for connecting to the cluster.
     */
    public Publication ingressPublication()
    {
        return publication;
    }

    /**
     * Get the raw {@link Subscription} for receiving from the cluster.
     * <p>
     * The can be wrapped with a {@link EgressAdapter} for dispatching events from the cluster.
     *
     * @return the raw {@link Subscription} for receiving from the cluster.
     */
    public Subscription egressSubscription()
    {
        return subscription;
    }

    /**
     * Send a keep alive message to the cluster to keep this session open.
     *
     * @return true if successfully sent otherwise false.
     */
    public boolean sendKeepAlive()
    {
        lock.lock();
        try
        {
            idleStrategy.reset();
            final int length = MessageHeaderEncoder.ENCODED_LENGTH + SessionKeepAliveRequestEncoder.BLOCK_LENGTH;
            int attempts = SEND_ATTEMPTS;

            while (true)
            {
                final long result = publication.tryClaim(length, bufferClaim);

                if (result > 0)
                {
                    keepAliveRequestEncoder
                        .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                        .correlationId(0L)
                        .clusterSessionId(clusterSessionId);

                    bufferClaim.commit();

                    return true;
                }

                checkResult(result);

                if (--attempts <= 0)
                {
                    break;
                }

                idleStrategy.idle();
            }

            return false;
        }
        finally
        {
            lock.unlock();
        }
    }

    private void closeSession()
    {
        idleStrategy.reset();
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + SessionCloseRequestEncoder.BLOCK_LENGTH;
        final SessionCloseRequestEncoder sessionCloseRequestEncoder = new SessionCloseRequestEncoder();
        int attempts = SEND_ATTEMPTS;

        while (true)
        {
            final long result = publication.tryClaim(length, bufferClaim);

            if (result > 0)
            {
                sessionCloseRequestEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .clusterSessionId(clusterSessionId);

                bufferClaim.commit();
                break;
            }

            checkResult(result);

            if (--attempts <= 0)
            {
                break;
            }

            idleStrategy.idle();
        }
    }

    private Publication connectToCluster()
    {
        Publication publication = null;
        final String ingressChannel = ctx.ingressChannel();
        final int ingressStreamId = ctx.ingressStreamId();
        final long deadlineNs = nanoClock.nanoTime() + ctx.messageTimeoutNs();

        if (isUnicast)
        {
            final ChannelUri channelUri = ChannelUri.parse(ingressChannel);
            final String[] memberEndpoints = ctx.clusterMemberEndpoints();
            final int memberCount = memberEndpoints.length;
            final Publication[] publications = new Publication[memberCount];

            for (int i = 0; i < memberCount; i++)
            {
                channelUri.put(CommonContext.ENDPOINT_PARAM_NAME, memberEndpoints[i]);
                final String channel = channelUri.toString();
                publications[i] = addIngressPublication(channel, ingressStreamId);
            }

            int connectedIndex = -1;
            while (true)
            {
                for (int i = 0; i < memberCount; i++)
                {
                    if (publications[i].isConnected())
                    {
                        connectedIndex = i;
                        break;
                    }
                }

                if (-1 != connectedIndex)
                {
                    for (int i = 0; i < memberCount; i++)
                    {
                        if (i == connectedIndex)
                        {
                            publication = publications[i];
                        }
                        else
                        {
                            publications[i].close();
                        }
                    }

                    break;
                }

                if (nanoClock.nanoTime() > deadlineNs)
                {
                    throw new TimeoutException("Awaiting connection to cluster");
                }

                idleStrategy.idle();
            }
        }
        else
        {
            publication = addIngressPublication(ingressChannel, ingressStreamId);

            idleStrategy.reset();
            while (!publication.isConnected())
            {
                if (nanoClock.nanoTime() > deadlineNs)
                {
                    throw new TimeoutException("Awaiting connection to cluster");
                }

                idleStrategy.idle();
            }
        }

        return publication;
    }

    private Publication addIngressPublication(final String channel, final int streamId)
    {
        if (ctx.isIngressExclusive())
        {
            return aeron.addExclusivePublication(channel, streamId);
        }
        else
        {
            return aeron.addPublication(channel, streamId);
        }
    }

    private long openSession()
    {
        final long deadlineNs = nanoClock.nanoTime() + ctx.messageTimeoutNs();
        long correlationId = sendConnectRequest(ctx.credentialsSupplier().connectRequestCredentialData(), deadlineNs);
        final EgressPoller poller = new EgressPoller(subscription, FRAGMENT_LIMIT);

        while (true)
        {
            pollNextResponse(deadlineNs, correlationId, poller);

            if (poller.correlationId() == correlationId)
            {
                if (poller.challenged())
                {
                    final byte[] credentialData = ctx.credentialsSupplier().onChallenge(poller.challengeData());
                    correlationId = sendChallengeResponse(poller.clusterSessionId(), credentialData, deadlineNs);
                    continue;
                }

                switch (poller.eventCode())
                {
                    case OK:
                        return poller.clusterSessionId();

                    case ERROR:
                        throw new AuthenticationException(poller.detail());

                    case AUTHENTICATION_REJECTED:
                        throw new AuthenticationException(poller.detail());
                }
            }
        }
    }

    private void pollNextResponse(final long deadlineNs, final long correlationId, final EgressPoller poller)
    {
        idleStrategy.reset();

        while (poller.poll() <= 0 && !poller.isPollComplete())
        {
            if (nanoClock.nanoTime() > deadlineNs)
            {
                throw new TimeoutException("Awaiting response for correlationId=" + correlationId);
            }

            idleStrategy.idle();
        }
    }

    private long sendConnectRequest(final byte[] credentialData, final long deadlineNs)
    {
        final long correlationId = aeron.nextCorrelationId();

        final SessionConnectRequestEncoder sessionConnectRequestEncoder = new SessionConnectRequestEncoder();
        final int length = MessageHeaderEncoder.ENCODED_LENGTH +
            SessionConnectRequestEncoder.BLOCK_LENGTH +
            SessionConnectRequestEncoder.responseChannelHeaderLength() +
            ctx.egressChannel().length() +
            SessionConnectRequestEncoder.credentialDataHeaderLength() +
            credentialData.length;

        idleStrategy.reset();

        while (true)
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                sessionConnectRequestEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .correlationId(correlationId)
                    .responseStreamId(ctx.egressStreamId())
                    .responseChannel(ctx.egressChannel())
                    .putCredentialData(credentialData, 0, credentialData.length);

                bufferClaim.commit();

                break;
            }

            if (Publication.CLOSED == result)
            {
                throw new IllegalStateException("Unexpected close from cluster");
            }

            if (nanoClock.nanoTime() > deadlineNs)
            {
                throw new TimeoutException("Failed to connect to cluster");
            }

            idleStrategy.idle();
        }

        return correlationId;
    }

    private long sendChallengeResponse(final long sessionId, final byte[] credentialData, final long deadlineNs)
    {
        final long correlationId = aeron.nextCorrelationId();

        final ChallengeResponseEncoder challengeResponseEncoder = new ChallengeResponseEncoder();
        final int length = MessageHeaderEncoder.ENCODED_LENGTH +
            ChallengeResponseEncoder.BLOCK_LENGTH +
            ChallengeResponseEncoder.credentialDataHeaderLength() +
            credentialData.length;

        idleStrategy.reset();

        while (true)
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                challengeResponseEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .correlationId(correlationId)
                    .clusterSessionId(sessionId)
                    .putCredentialData(credentialData, 0, credentialData.length);

                bufferClaim.commit();

                break;
            }

            checkResult(result);

            if (nanoClock.nanoTime() > deadlineNs)
            {
                throw new TimeoutException("Failed to connect to cluster");
            }

            idleStrategy.idle();
        }

        return correlationId;
    }

    private static void checkResult(final long result)
    {
        if (result == Publication.NOT_CONNECTED ||
            result == Publication.CLOSED ||
            result == Publication.MAX_POSITION_EXCEEDED)
        {
            throw new IllegalStateException("Unexpected publication state: " + result);
        }
    }

    /**
     * Configuration options for cluster client.
     */
    public static class Configuration
    {
        /**
         * Timeout when waiting on a message to be sent or received.
         */
        public static final String MESSAGE_TIMEOUT_PROP_NAME = "aeron.cluster.message.timeout";

        /**
         * Timeout when waiting on a message to be sent or received. Default to 5 seconds in nanoseconds.
         */
        public static final long MESSAGE_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(5);

        /**
         * Property name for the comma separated list of cluster member endpoints for use with unicast.
         * <p>
         * Each member of the list will be substituted for the endpoint in the {@link #INGRESS_CHANNEL_PROP_NAME} value.
         */
        public static final String CLUSTER_MEMBER_ENDPOINTS_PROP_NAME = "aeron.cluster.member.endpoints";

        /**
         * Property name for the comma separated list of cluster member endpoints. Default of null is for multicast.
         */
        public static final String CLUSTER_MEMBER_ENDPOINTS_DEFAULT = null;

        /**
         * Channel for sending messages to a cluster. Ideally this will be a multicast address otherwise unicast will
         * be required and the {@link #CLUSTER_MEMBER_ENDPOINTS_PROP_NAME} is used to substitute the endpoints.
         */
        public static final String INGRESS_CHANNEL_PROP_NAME = "aeron.cluster.ingress.channel";

        /**
         * Channel for sending messages to a cluster. Default to localhost:9010 for testing.
         */
        public static final String INGRESS_CHANNEL_DEFAULT = "aeron:udp?endpoint=localhost:9010";

        /**
         * Stream id within a channel for sending messages to a cluster.
         */
        public static final String INGRESS_STREAM_ID_PROP_NAME = "aeron.cluster.ingress.stream.id";

        /**
         * Stream id within a channel for sending messages to a cluster. Default to stream id of 1.
         */
        public static final int INGRESS_STREAM_ID_DEFAULT = 1;

        /**
         * Channel for receiving response messages from a cluster.
         */
        public static final String EGRESS_CHANNEL_PROP_NAME = "aeron.cluster.egress.channel";

        /**
         * Channel for receiving response messages from a cluster. Default to localhost:9020 for testing.
         */
        public static final String EGRESS_CHANNEL_DEFAULT = "aeron:udp?endpoint=localhost:9020";

        /**
         * Stream id within a channel for receiving messages from a cluster.
         */
        public static final String EGRESS_STREAM_ID_PROP_NAME = "aeron.archive.control.response.stream.id";

        /**
         * Stream id within a channel for receiving messages from a cluster. Default to stream id of 2.
         */
        public static final int EGRESS_STREAM_ID_DEFAULT = 2;

        /**
         * The timeout in nanoseconds to wait for a message.
         *
         * @return timeout in nanoseconds to wait for a message.
         * @see #MESSAGE_TIMEOUT_PROP_NAME
         */
        public static long messageTimeoutNs()
        {
            return getDurationInNanos(MESSAGE_TIMEOUT_PROP_NAME, MESSAGE_TIMEOUT_DEFAULT_NS);
        }

        /**
         * The value {@link #CLUSTER_MEMBER_ENDPOINTS_DEFAULT} or system property
         * {@link #CLUSTER_MEMBER_ENDPOINTS_PROP_NAME} if set.
         *
         * @return {@link #CLUSTER_MEMBER_ENDPOINTS_DEFAULT} or system property
         * {@link #CLUSTER_MEMBER_ENDPOINTS_PROP_NAME} if set.
         */
        public static String[] clusterMemberEndpoints()
        {
            final String memberEndpoints = System.getProperty(
                CLUSTER_MEMBER_ENDPOINTS_PROP_NAME, CLUSTER_MEMBER_ENDPOINTS_DEFAULT);

            return null == memberEndpoints ? null : memberEndpoints.split(",");
        }

        /**
         * The value {@link #INGRESS_CHANNEL_DEFAULT} or system property
         * {@link #INGRESS_CHANNEL_PROP_NAME} if set.
         *
         * @return {@link #INGRESS_CHANNEL_DEFAULT} or system property
         * {@link #INGRESS_CHANNEL_PROP_NAME} if set.
         */
        public static String ingressChannel()
        {
            return System.getProperty(INGRESS_CHANNEL_PROP_NAME, INGRESS_CHANNEL_DEFAULT);
        }

        /**
         * The value {@link #INGRESS_STREAM_ID_DEFAULT} or system property
         * {@link #INGRESS_STREAM_ID_PROP_NAME} if set.
         *
         * @return {@link #INGRESS_STREAM_ID_DEFAULT} or system property
         * {@link #INGRESS_STREAM_ID_PROP_NAME} if set.
         */
        public static int ingressStreamId()
        {
            return Integer.getInteger(INGRESS_STREAM_ID_PROP_NAME, INGRESS_STREAM_ID_DEFAULT);
        }

        /**
         * The value {@link #EGRESS_CHANNEL_DEFAULT} or system property
         * {@link #EGRESS_CHANNEL_PROP_NAME} if set.
         *
         * @return {@link #EGRESS_CHANNEL_DEFAULT} or system property
         * {@link #EGRESS_CHANNEL_PROP_NAME} if set.
         */
        public static String egressChannel()
        {
            return System.getProperty(EGRESS_CHANNEL_PROP_NAME, EGRESS_CHANNEL_DEFAULT);
        }

        /**
         * The value {@link #EGRESS_STREAM_ID_DEFAULT} or system property
         * {@link #EGRESS_STREAM_ID_PROP_NAME} if set.
         *
         * @return {@link #EGRESS_STREAM_ID_DEFAULT} or system property
         * {@link #EGRESS_STREAM_ID_PROP_NAME} if set.
         */
        public static int egressStreamId()
        {
            return Integer.getInteger(EGRESS_STREAM_ID_PROP_NAME, EGRESS_STREAM_ID_DEFAULT);
        }
    }

    /**
     * Context for cluster session and connection.
     */
    public static class Context implements AutoCloseable
    {
        private long messageTimeoutNs = Configuration.messageTimeoutNs();
        private String[] clusterMemberEndpoints = Configuration.clusterMemberEndpoints();
        private String ingressChannel = Configuration.ingressChannel();
        private int ingressStreamId = Configuration.ingressStreamId();
        private String egressChannel = Configuration.egressChannel();
        private int egressStreamId = Configuration.egressStreamId();
        private IdleStrategy idleStrategy;
        private Lock lock;
        private String aeronDirectoryName = CommonContext.AERON_DIR_PROP_DEFAULT;
        private Aeron aeron;
        private CredentialsSupplier credentialsSupplier;
        private boolean ownsAeronClient = true;
        private boolean isIngressExclusive = true;

        public void conclude()
        {
            if (null == aeron)
            {
                aeron = Aeron.connect(new Aeron.Context()
                    .aeronDirectoryName(aeronDirectoryName));
            }

            if (null == idleStrategy)
            {
                idleStrategy = new BackoffIdleStrategy(1, 10, 1, 1);
            }

            if (null == lock)
            {
                lock = new ReentrantLock();
            }

            if (null == credentialsSupplier)
            {
                credentialsSupplier = new NullCredentialsSupplier();
            }
        }

        /**
         * Set the message timeout in nanoseconds to wait for sending or receiving a message.
         *
         * @param messageTimeoutNs to wait for sending or receiving a message.
         * @return this for a fluent API.
         * @see Configuration#MESSAGE_TIMEOUT_PROP_NAME
         */
        public Context messageTimeoutNs(final long messageTimeoutNs)
        {
            this.messageTimeoutNs = messageTimeoutNs;
            return this;
        }

        /**
         * The message timeout in nanoseconds to wait for sending or receiving a message.
         *
         * @return the message timeout in nanoseconds to wait for sending or receiving a message.
         * @see Configuration#MESSAGE_TIMEOUT_PROP_NAME
         */
        public long messageTimeoutNs()
        {
            return messageTimeoutNs;
        }

        /**
         * The endpoints representing members for use with unicast. A null value can be used when multicast.
         *
         * @param clusterMembers which are all candidates to be leader.
         * @return this for a fluent API.
         * @see Configuration#CLUSTER_MEMBER_ENDPOINTS_PROP_NAME
         */
        public Context clusterMemberEndpoints(final String... clusterMembers)
        {
            this.clusterMemberEndpoints = clusterMembers;
            return this;
        }

        /**
         * The endpoints representing members for use with unicast. A null value can be used when multicast.
         *
         * @return members of the cluster which are all candidates to be leader.
         * @see Configuration#CLUSTER_MEMBER_ENDPOINTS_PROP_NAME
         */
        public String[] clusterMemberEndpoints()
        {
            return clusterMemberEndpoints;
        }

        /**
         * Set the channel parameter for the ingress channel.
         *
         * @param channel parameter for the ingress channel.
         * @return this for a fluent API.
         * @see Configuration#INGRESS_CHANNEL_PROP_NAME
         */
        public Context ingressChannel(final String channel)
        {
            ingressChannel = channel;
            return this;
        }

        /**
         * Get the channel parameter for the ingress channel.
         *
         * @return the channel parameter for the ingress channel.
         * @see Configuration#INGRESS_CHANNEL_PROP_NAME
         */
        public String ingressChannel()
        {
            return ingressChannel;
        }

        /**
         * Set the stream id for the ingress channel.
         *
         * @param streamId for the ingress channel.
         * @return this for a fluent API
         * @see Configuration#INGRESS_STREAM_ID_PROP_NAME
         */
        public Context ingressStreamId(final int streamId)
        {
            ingressStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the ingress channel.
         *
         * @return the stream id for the ingress channel.
         * @see Configuration#INGRESS_STREAM_ID_PROP_NAME
         */
        public int ingressStreamId()
        {
            return ingressStreamId;
        }

        /**
         * Set the channel parameter for the egress channel.
         *
         * @param channel parameter for the egress channel.
         * @return this for a fluent API.
         * @see Configuration#EGRESS_CHANNEL_PROP_NAME
         */
        public Context egressChannel(final String channel)
        {
            egressChannel = channel;
            return this;
        }

        /**
         * Get the channel parameter for the egress channel.
         *
         * @return the channel parameter for the egress channel.
         * @see Configuration#EGRESS_CHANNEL_PROP_NAME
         */
        public String egressChannel()
        {
            return egressChannel;
        }

        /**
         * Set the stream id for the egress channel.
         *
         * @param streamId for the egress channel.
         * @return this for a fluent API
         * @see Configuration#EGRESS_STREAM_ID_PROP_NAME
         */
        public Context egressStreamId(final int streamId)
        {
            egressStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the egress channel.
         *
         * @return the stream id for the egress channel.
         * @see Configuration#EGRESS_STREAM_ID_PROP_NAME
         */
        public int egressStreamId()
        {
            return egressStreamId;
        }

        /**
         * Set the {@link IdleStrategy} used when waiting for responses.
         *
         * @param idleStrategy used when waiting for responses.
         * @return this for a fluent API.
         */
        public Context idleStrategy(final IdleStrategy idleStrategy)
        {
            this.idleStrategy = idleStrategy;
            return this;
        }

        /**
         * Get the {@link IdleStrategy} used when waiting for responses.
         *
         * @return the {@link IdleStrategy} used when waiting for responses.
         */
        public IdleStrategy idleStrategy()
        {
            return idleStrategy;
        }

        /**
         * Set the top level Aeron directory used for communication between the Aeron client and Media Driver.
         *
         * @param aeronDirectoryName the top level Aeron directory.
         * @return this for a fluent API.
         */
        public Context aeronDirectoryName(final String aeronDirectoryName)
        {
            this.aeronDirectoryName = aeronDirectoryName;
            return this;
        }

        /**
         * Get the top level Aeron directory used for communication between the Aeron client and Media Driver.
         *
         * @return The top level Aeron directory.
         */
        public String aeronDirectoryName()
        {
            return aeronDirectoryName;
        }

        /**
         * {@link Aeron} client for communicating with the local Media Driver.
         * <p>
         * This client will be closed when the {@link AeronCluster#close()} or {@link #close()} methods are called if
         * {@link #ownsAeronClient()} is true.
         *
         * @param aeron client for communicating with the local Media Driver.
         * @return this for a fluent API.
         * @see Aeron#connect()
         */
        public Context aeron(final Aeron aeron)
        {
            this.aeron = aeron;
            return this;
        }

        /**
         * {@link Aeron} client for communicating with the local Media Driver.
         * <p>
         * If not provided then a default will be established during {@link #conclude()} by calling
         * {@link Aeron#connect()}.
         *
         * @return client for communicating with the local Media Driver.
         */
        public Aeron aeron()
        {
            return aeron;
        }

        /**
         * Does this context own the {@link #aeron()} client and this takes responsibility for closing it?
         *
         * @param ownsAeronClient does this context own the {@link #aeron()} client.
         * @return this for a fluent API.
         */
        public Context ownsAeronClient(final boolean ownsAeronClient)
        {
            this.ownsAeronClient = ownsAeronClient;
            return this;
        }

        /**
         * Does this context own the {@link #aeron()} client and this takes responsibility for closing it?
         *
         * @return does this context own the {@link #aeron()} client and this takes responsibility for closing it?
         */
        public boolean ownsAeronClient()
        {
            return ownsAeronClient;
        }

        /**
         * The {@link Lock} that is used to provide mutual exclusion in the {@link AeronCluster} client.
         * <p>
         * If the {@link AeronCluster} is used from only a single thread then the lock can be set to
         * {@link NoOpLock} to elide the lock overhead.
         *
         * @param lock that is used to provide mutual exclusion in the {@link AeronCluster} client.
         * @return this for a fluent API.
         */
        public Context lock(final Lock lock)
        {
            this.lock = lock;
            return this;
        }

        /**
         * Get the {@link Lock} that is used to provide mutual exclusion in the {@link AeronCluster} client.
         *
         * @return the {@link Lock} that is used to provide mutual exclusion in the {@link AeronCluster} client.
         */
        public Lock lock()
        {
            return lock;
        }

        /**
         * Is ingress to the cluster exclusively from a single thread for this client?
         *
         * @param isIngressExclusive true if ingress to the cluster is exclusively from a single thread for this client?
         * @return this for a fluent API.
         */
        public Context isIngressExclusive(final boolean isIngressExclusive)
        {
            this.isIngressExclusive = isIngressExclusive;
            return this;
        }

        /**
         * Is ingress to the cluster exclusively from a single thread for this client?
         *
         * @return true if ingress to the cluster exclusively from a single thread for this client?
         */
        public boolean isIngressExclusive()
        {
            return isIngressExclusive;
        }

        /**
         * Get the {@link CredentialsSupplier} to be used for authentication with the cluster.
         *
         * @return the {@link CredentialsSupplier} to be used for authentication with the cluster.
         */
        public CredentialsSupplier credentialsSupplier()
        {
            return credentialsSupplier;
        }

        /**
         * Set the {@link CredentialsSupplier} to be used for authentication with the cluster.
         *
         * @param credentialsSupplier to be used for authentication with the cluster.
         * @return this for fluent API.
         */
        public Context credentialsSupplier(final CredentialsSupplier credentialsSupplier)
        {
            this.credentialsSupplier = credentialsSupplier;
            return this;
        }

        /**
         * Close the context and free applicable resources.
         * <p>
         * If the {@link #ownsAeronClient()} is true then the {@link #aeron()} client will be closed.
         */
        public void close()
        {
            if (ownsAeronClient)
            {
                CloseHelper.close(aeron);
            }
        }
    }
}