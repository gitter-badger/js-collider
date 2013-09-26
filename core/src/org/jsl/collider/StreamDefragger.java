/*
 * JS-Collider framework.
 * Copyright (C) 2013 Sergey Zubarev
 * info@js-labs.org
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jsl.collider;

import java.nio.ByteBuffer;

/*
 * Helper class providing byte stream defragmentation functionality.
 * Useful for binary protocols with messages having a fixed length header
 * containing the length of the whole message.
 *
 * Constructor argument is a length of the message header.
 *
 * Derived class is supposed to implement method <tt>validateHeader</tt>
 * which is called when whole header is available for decoding and
 * should return the length of the message.
 * Method <tt>getNext</tt> returns the next available whole message,
 * or null if no message is available.
 *
 * Here is a usage example with Session.Listener
 * for simple message header containing only 4-bytes length field:
 *
 * <pre>{@code
 *   class ServerListener implements Session.Listener
 *   {
 *       private final StreamDefragger streamDefragger = new StreamDefragger(4)
 *       {
 *           protected int validateHeader( ByteBuffer header )
 *           {
 *               return header.getInt();
 *           }
 *       };
 *
 *       public void onDataReceived( ByteBuffer data )
 *       {
 *           ByteBuffer msg = streamDefragger.getNext( data );
 *           while (msg != null)
 *           {
 *               // process message
 *               msg = streamDefragger.getNext();
 *           }
 *       }
 *
 *       public void onConnectionClose()
 *       {
 *       }
 *   }
 * }</pre>
 */

public abstract class StreamDefragger
{
    public final static ByteBuffer INVALID_HEADER = ByteBuffer.allocate(0);

    private final int m_headerSize;
    private ByteBuffer m_buf;
    private ByteBuffer m_data;
    private int m_packetLen;
    private int m_pos;
    private int m_limit;

    private static boolean copyData( ByteBuffer dst, ByteBuffer src, int bytes )
    {
        int pos = src.position();
        int limit = src.limit();
        int available = (limit - pos);
        if (available < bytes)
        {
            dst.put( src );
            return false;
        }
        else
        {
            src.limit( pos + bytes );
            dst.put( src );
            src.limit( limit );
            return true;
        }
    }

    private static ByteBuffer getBuffer( int capacity )
    {
        if (capacity < 1024)
            capacity = 1024;
        return ByteBuffer.allocate( capacity );
    }

    public StreamDefragger( int headerSize )
    {
        m_headerSize = headerSize;
    }

    public final ByteBuffer getNext( ByteBuffer data )
    {
        assert( m_data == null );

        if ((m_buf != null) && (m_buf.position() > 0))
        {
            int pos = m_buf.position();
            if (pos < m_headerSize)
            {
                int cc = (m_headerSize - pos);
                if (!copyData(m_buf, data, cc))
                    return null;

                m_buf.flip();
                m_packetLen = validateHeader( m_buf );
                if (m_packetLen <= 0)
                    return INVALID_HEADER;

                if (m_buf.capacity() < m_packetLen)
                {
                    ByteBuffer buf = ByteBuffer.allocate( m_packetLen );
                    m_buf.position( 0 );
                    buf.put( m_buf );
                    m_buf = buf;
                }
                else
                    m_buf.limit( m_buf.capacity() );

                pos = m_headerSize;
                m_buf.position( pos );
            }

            int cc = (m_packetLen - pos);
            if (!copyData(m_buf, data, cc))
                return null;
            m_buf.flip();

            m_data = data;
            m_pos = data.position();
            m_limit = data.limit();

            return m_buf;
        }

        m_data = data;
        m_pos = data.position();
        m_limit = data.limit();
        return getNext();
    }

    public final ByteBuffer getNext()
    {
        m_data.position( m_pos );
        m_data.limit( m_limit );

        int bytesRest = (m_limit - m_pos);
        if (bytesRest == 0)
        {
            if (m_buf != null)
                m_buf.clear();
            m_data = null;
            return null;
        }

        if (bytesRest < m_headerSize)
        {
            if (m_buf == null)
                m_buf = getBuffer( m_headerSize );
            else
                m_buf.clear();
            m_buf.put( m_data );
            m_data = null;
            return null;
        }

        m_packetLen = validateHeader( m_data );
        m_data.position( m_pos );

        if (m_packetLen <= 0)
            return INVALID_HEADER;

        if (bytesRest < m_packetLen)
        {
            if ((m_buf == null) || (m_buf.capacity() < m_packetLen))
                m_buf = getBuffer( m_packetLen );
            else
                m_buf.clear();
            m_buf.put( m_data );
            m_data = null;
            return null;
        }

        m_pos += m_packetLen;
        m_data.limit( m_pos );
        return m_data;
    }

    abstract protected int validateHeader( ByteBuffer header );
}
