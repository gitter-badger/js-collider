/*
 * JS-Collider framework tests.
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

package org.jsl.tests.send_throughput;

import org.jsl.collider.Acceptor;
import org.jsl.collider.Collider;
import org.jsl.collider.Session;
import org.jsl.tests.Util;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class Server
{
    private final Client m_client;
    private final int m_socketBufferSize;
    private final Semaphore m_semStart;
    private final AtomicInteger m_sessionsConnected;
    private final AtomicInteger m_sessionsReady;
    private final AtomicInteger m_sessionsDone;
    private volatile Sender [] m_sender;
    private int m_messages;
    private ByteBuffer m_msg;

    private class ServerListener implements Session.Listener
    {
        private Session m_session;

        public ServerListener( Session session )
        {
            m_session = session;
            System.out.println( "Connection accepted from " + session.getRemoteAddress() );
        }

        public void onDataReceived( ByteBuffer data )
        {
            /* We expect only one small message from the client,
             * does not make a sense to handle possible fragmentation.
             */
            data.getInt(); // skip packet length
            final int sessionsExpected = data.getInt();
            int sessions = m_sessionsConnected.getAndIncrement();
            if (sessions == 0)
            {
                m_messages = data.getInt();
                int messageLength = data.getInt();
                if (messageLength < 4)
                    messageLength = 4;
                m_msg = ByteBuffer.allocateDirect( messageLength );
                m_msg.putInt( messageLength );
                for (int idx=4; idx<messageLength; idx++)
                    m_msg.put( (byte) idx );
                m_msg.position(0);
                m_sessionsDone.set( sessionsExpected );
                m_sender = new Sender[sessionsExpected];
            }
            else
                while (m_sender == null);

            m_sender[sessions] = new Sender( m_session );
            m_sender[sessions].start();

            sessions = m_sessionsReady.incrementAndGet();
            if (sessions == sessionsExpected)
                m_semStart.release( sessionsExpected );
        }

        public void onConnectionClosed()
        {
            System.out.println( "Connection closed to " + m_session.getRemoteAddress() );
        }
    }

    private class Sender extends Thread
    {
        private final Session m_session;

        public Sender( Session session )
        {
            m_session = session;
        }

        public void run()
        {
            int messages = m_messages;
            try { m_semStart.acquire(); }
            catch (InterruptedException ex) { ex.printStackTrace(); }

            long startTime = System.nanoTime();
            for (; messages>0; messages--)
                m_session.sendData( m_msg.duplicate() );
            long endTime = System.nanoTime();

            System.out.println(
                    "Sent " + m_messages + " messages at " +
                    Util.formatDelay(startTime, endTime) + " sec." );

            m_session.closeConnection();
            int sessions = m_sessionsDone.decrementAndGet();
            if (sessions == 0)
                m_session.getCollider().stop();
        }
    }

    private class TestAcceptor extends Acceptor
    {
        public TestAcceptor()
        {
            super( new InetSocketAddress(0) );
            tcpNoDelay = true;
            socketSendBufSize = m_socketBufferSize;
        }

        public void onAcceptorStarted( int localPort )
        {
            System.out.println( "Server started at port " + localPort );
            if (m_client != null)
                m_client.start( new InetSocketAddress("localhost", localPort) );
        }

        public Session.Listener createSessionListener( Session session )
        {
            return new ServerListener( session );
        }
    }

    public Server( Client client, int socketBufferSize )
    {
        m_client = client;
        m_socketBufferSize = socketBufferSize;
        m_semStart = new Semaphore(0);
        m_sessionsConnected = new AtomicInteger();
        m_sessionsReady = new AtomicInteger();
        m_sessionsDone = new AtomicInteger();
    }

    public void run()
    {
        Collider collider = Collider.create();
        collider.addAcceptor( new TestAcceptor() );
        collider.run();
        m_client.stopAndWait();
    }
}
