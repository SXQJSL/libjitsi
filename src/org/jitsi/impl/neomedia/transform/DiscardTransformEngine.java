/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.neomedia.transform;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;

import javax.media.*;
import java.util.*;

/**
 * As the name suggests, the DiscardTransformEngine discards packets that are
 * flagged for discard. The packets that are passed on in the chain have their
 * sequence numbers rewritten hiding the gaps created by the dropped packets.
 *
 * Instances of this class are not thread-safe. If multiple threads access an
 * instance concurrently, it must be synchronized externally.
 *
 * @author George Politis
 */
public class DiscardTransformEngine
    implements TransformEngine
{
    /**
     * A map of source ssrc to {@link ResumableStreamRewriter}.
     */
    private final Map<Long, ResumableStreamRewriter> ssrcToRewriter
        = new HashMap<>();

    /**
     * The {@link PacketTransformer} for RTCP packets.
     */
    private final PacketTransformer rtpTransformer
        = new SinglePacketTransformerAdapter()
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            if (pkt == null)
            {
                return null;
            }

            boolean dropPkt
                = (pkt.getFlags() & Buffer.FLAG_DISCARD) == Buffer.FLAG_DISCARD;

            long ssrc = pkt.getSSRCAsLong();
            ResumableStreamRewriter rewriter = ssrcToRewriter.get(ssrc);
            if (rewriter == null)
            {
                rewriter = new ResumableStreamRewriter();
                ssrcToRewriter.put(ssrc, rewriter);
            }

            rewriter.rewriteRTP(
                !dropPkt, pkt.getBuffer(), pkt.getOffset(), pkt.getLength());

            return dropPkt ? null : pkt;
        }
    };

    /**
     * The {@link PacketTransformer} for RTP packets.
     */
    private final PacketTransformer rtcpTransformer
        = new SinglePacketTransformerAdapter()
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            if (pkt == null)
            {
                return pkt;
            }

            byte[] buf = pkt.getBuffer();
            int offset = pkt.getOffset(), length =  pkt.getLength();

            while (length > 0)
            {
                // Check RTCP packet validity. This makes sure that pktLen > 0
                // so this loop will eventually terminate.
                if (!RTCPHeaderUtils.isValid(buf, offset, length))
                {
                    break;
                }

                int pktLen = RTCPHeaderUtils.getLength(buf, offset, length);

                int pt = RTCPHeaderUtils.getPacketType(buf, offset, pktLen);
                if (pt == RTCPPacket.SR)
                {
                    long ssrc
                        = RTCPHeaderUtils.getSenderSSRC(buf, offset, pktLen);

                    ResumableStreamRewriter rewriter = ssrcToRewriter.get(ssrc);

                    if (rewriter != null)
                    {
                        rewriter.processRTCP(
                            true /* rewrite */, buf, offset, pktLen);
                    }
                }

                offset += pktLen;
                length -= pktLen;
            }

            return pkt;
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return rtpTransformer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return rtcpTransformer;
    }
}