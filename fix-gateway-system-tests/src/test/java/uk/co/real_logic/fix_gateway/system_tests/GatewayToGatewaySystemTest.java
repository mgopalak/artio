/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.system_tests;

import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.fix_gateway.engine.FixEngine;
import uk.co.real_logic.fix_gateway.engine.SessionInfo;
import uk.co.real_logic.fix_gateway.engine.framer.LibraryInfo;
import uk.co.real_logic.fix_gateway.library.FixLibrary;
import uk.co.real_logic.fix_gateway.messages.SessionReplyStatus;
import uk.co.real_logic.fix_gateway.messages.SessionState;
import uk.co.real_logic.fix_gateway.session.Session;

import java.util.List;
import java.util.function.IntSupplier;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static uk.co.real_logic.fix_gateway.FixMatchers.*;
import static uk.co.real_logic.fix_gateway.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.fix_gateway.Timing.assertEventuallyTrue;
import static uk.co.real_logic.fix_gateway.decoder.Constants.MSG_SEQ_NUM;
import static uk.co.real_logic.fix_gateway.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.fix_gateway.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.fix_gateway.messages.SessionReplyStatus.OK;
import static uk.co.real_logic.fix_gateway.messages.SessionReplyStatus.SEQUENCE_NUMBER_TOO_HIGH;
import static uk.co.real_logic.fix_gateway.messages.SessionState.DISABLED;
import static uk.co.real_logic.fix_gateway.system_tests.SystemTestUtil.*;

public class GatewayToGatewaySystemTest extends AbstractGatewayToGatewaySystemTest
{
    @Before
    public void launch()
    {
        delete(ACCEPTOR_LOGS);

        mediaDriver = launchMediaDriver();

        launchAcceptingEngine();
        initiatingEngine = launchInitiatingGateway(libraryAeronPort);

        acceptingLibrary = newAcceptingLibrary(acceptingHandler);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler);

        connectSessions();
    }

    @Test
    public void messagesCanBeSentFromInitiatorToAcceptor()
    {
        messagesCanBeExchanged();

        assertInitiatingSequenceIndexIs(0);
    }

    @Test
    public void messagesCanBeSentFromInitiatorToAcceptingLibrary()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged();

        assertSequenceIndicesAre(0);
    }

    @Test
    public void gatewayProcessesResendRequests()
    {
        acquireAcceptingSession();

        messagesCanBeSentFromInitiatorToAcceptor();

        final int sequenceNumber = sendResendRequest();

        assertMessageResent(sequenceNumber);

        assertSequenceIndicesAre(0);
    }

    @Test
    public void messagesCanBeSentFromAcceptorToInitiator()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged(acceptingSession, acceptingLibrary, initiatingLibrary, acceptingOtfAcceptor);

        assertSequenceIndicesAre(0);
    }

    @Test
    public void initiatorSessionCanBeDisconnected()
    {
        acquireAcceptingSession();

        initiatingSession.startLogout();

        assertSessionsDisconnected();

        assertSequenceIndicesAre(0);
    }

    @Test
    public void acceptorSessionCanBeDisconnected()
    {
        acquireAcceptingSession();

        logoutAcceptingSession();

        assertSessionsDisconnected();

        assertSequenceIndicesAre(0);
    }

    @Test
    public void sessionsCanReconnect()
    {
        acquireAcceptingSession();

        acceptingSession.startLogout();
        assertSessionsDisconnected();

        assertAllMessagesHaveSequenceIndex(0);
        clearMessages();

        wireSessions();

        messagesCanBeExchanged();

        assertSequenceIndicesAre(1);
    }

    @Test
    public void sessionsListedInAdminApi()
    {
        final List<LibraryInfo> libraries = libraries(initiatingEngine);
        assertThat(libraries, hasSize(2));

        final LibraryInfo library = libraries.get(0);
        assertEquals(initiatingLibrary.libraryId(), library.libraryId());

        final List<SessionInfo> sessions = library.sessions();
        assertThat(sessions, hasSize(1));

        final SessionInfo sessionInfo = sessions.get(0);
        assertThat(sessionInfo.address(), containsString("localhost"));
        assertThat(sessionInfo.address(), containsString(String.valueOf(port)));
        assertEquals(initiatingSession.connectionId(), sessionInfo.connectionId());

        assertEquals(initiatingSession.connectedPort(), port);
        assertEquals(initiatingSession.connectedHost(), "localhost");

        final LibraryInfo gatewayLibraryInfo = libraries.get(1);
        assertEquals(ENGINE_LIBRARY_ID, gatewayLibraryInfo.libraryId());
        assertThat(gatewayLibraryInfo.sessions(), hasSize(0));
    }

    @Test
    public void multipleLibrariesCanExchangeMessages()
    {
        final int initiator1MessageCount = initiatingOtfAcceptor.messages().size();

        final FakeOtfAcceptor initiatingOtfAcceptor2 = new FakeOtfAcceptor();
        final FakeHandler initiatingSessionHandler2 = new FakeHandler(initiatingOtfAcceptor2);
        try (FixLibrary library2 = newInitiatingLibrary(libraryAeronPort, initiatingSessionHandler2))
        {
            acceptingHandler.clearSessions();
            final Session session2 = initiateAndAwait(library2, port, INITIATOR_ID2, ACCEPTOR_ID).resultIfPresent();

            assertConnected(session2);
            sessionLogsOn(library2, acceptingLibrary, session2);

            final long sessionId = acceptingHandler.awaitSessionIdFor(
                INITIATOR_ID2,
                ACCEPTOR_ID,
                () ->
                {
                    acceptingLibrary.poll(1);
                    library2.poll(1);
                    initiatingLibrary.poll(1);
                }, 1000);

            final Session acceptingSession2 = acquireSession(acceptingHandler, acceptingLibrary, sessionId);

            sendTestRequest(acceptingSession2);
            assertReceivedTestRequest(library2, acceptingLibrary, initiatingOtfAcceptor2);

            assertThat(session2, hasSequenceIndex(0));

            assertOriginalLibraryDoesNotReceiveMessages(initiator1MessageCount);
        }

        assertInitiatingSequenceIndexIs(0);
    }

    @Test
    public void sequenceNumbersShouldResetOverDisconnects()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged();
        assertSequenceFromInitToAcceptAt(2, 2);

        initiatingSession.startLogout();

        assertSequenceIndicesAre(0);
        clearMessages();
        assertSessionsDisconnected();

        wireSessions();

        assertSequenceFromInitToAcceptAt(1, 1);

        sendTestRequest(initiatingSession);
        assertReceivedTestRequest(initiatingLibrary, acceptingLibrary, acceptingOtfAcceptor);

        assertSequenceIndicesAre(1);
    }

    @Test
    public void acceptorsShouldHandleInitiatorDisconnectsGracefully()
    {
        acquireAcceptingSession();

        assertFalse("Premature Acceptor Disconnect", acceptingHandler.hasDisconnected());

        initiatingEngine.close();

        assertEventuallyTrue("Acceptor Disconnected",
            () ->
            {
                acceptingLibrary.poll(1);
                return acceptingHandler.hasDisconnected();
            });

        assertSequenceIndicesAre(0);
    }

    @Test
    public void librariesShouldBeAbleToReleaseInitiatedSessionToEngine()
    {
        acquireAcceptingSession();

        releaseSessionToEngine(initiatingSession, initiatingLibrary, initiatingEngine);
    }

    @Test
    public void librariesShouldBeAbleToReleaseAcceptedSessionToEngine()
    {
        acquireAcceptingSession();

        releaseSessionToEngine(acceptingSession, acceptingLibrary, acceptingEngine);
    }

    @Test
    public void librariesShouldBeAbleToAcquireReleasedInitiatedSessions()
    {
        acquireAcceptingSession();

        final long sessionId = initiatingSession.id();

        releaseToGateway(initiatingLibrary, initiatingSession);

        reacquireSession(
            initiatingSession, initiatingLibrary, initiatingEngine,
            sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY, OK);

        assertSequenceIndicesAre(0);
    }

    @Test
    public void librariesShouldBeAbleToAcquireReleasedAcceptedSessions()
    {
        acquireAcceptingSession();

        final long sessionId = acceptingSession.id();

        releaseToGateway(acceptingLibrary, acceptingSession);

        reacquireSession(
            acceptingSession, acceptingLibrary, acceptingEngine,
            sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY, OK);

        assertSequenceIndicesAre(0);
    }

    @Test
    public void shouldReceiveCatchupReplayAfterReconnect()
    {
        shouldReceiveCatchupReplay(() -> acceptingSession.sequenceIndex(), OK);
    }

    @Test
    public void shouldReceiveCatchupReplayForSequenceNumberTooHigh()
    {
        shouldReceiveCatchupReplay(() -> 100, SEQUENCE_NUMBER_TOO_HIGH);
    }

    private void shouldReceiveCatchupReplay(
        final IntSupplier sequenceIndexSupplier,
        final SessionReplyStatus expectedStatus)
    {
        acquireAcceptingSession();

        disconnectSessions();

        assertThat(initiatingLibrary.sessions(), hasSize(0));
        assertThat(acceptingLibrary.sessions(), hasSize(0));

        final long sessionId = acceptingSession.id();
        final int lastReceivedMsgSeqNum = acceptingSession.lastReceivedMsgSeqNum();
        final int sequenceIndex = sequenceIndexSupplier.getAsInt();

        assertSequenceIndicesAre(0);
        clearMessages();

        connectSessions();

        reacquireSession(
            acceptingSession, acceptingLibrary, acceptingEngine,
            sessionId, lastReceivedMsgSeqNum, sequenceIndex,
            expectedStatus);

        acceptingSession = acceptingHandler.lastSession();
        acceptingHandler.resetSession();

        // Send messages both ways to ensure that the session is setup
        messagesCanBeExchanged(acceptingSession, acceptingLibrary, initiatingLibrary, acceptingOtfAcceptor);
        messagesCanBeExchanged(initiatingSession, initiatingLibrary, acceptingLibrary, initiatingOtfAcceptor);

        assertSequenceIndicesAre(1);
    }

    @Test
    public void enginesShouldManageAcceptingSession()
    {
        acquireAcceptingSession();

        engineShouldManageSession(
            acceptingSession, acceptingLibrary, acceptingOtfAcceptor,
            initiatingSession, initiatingLibrary, initiatingOtfAcceptor);
    }

    @Test
    public void enginesShouldManageInitiatingSession()
    {
        acquireAcceptingSession();

        engineShouldManageSession(
            initiatingSession, initiatingLibrary, initiatingOtfAcceptor,
            acceptingSession, acceptingLibrary, acceptingOtfAcceptor);
    }

    @Test
    public void librariesShouldNotBeAbleToAcquireSessionsThatDontExist()
    {
        final SessionReplyStatus status = requestSession(initiatingLibrary, 42, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY);

        assertEquals(SessionReplyStatus.UNKNOWN_SESSION, status);
    }

    @Test
    public void librariesShouldBeNotifiedOfGatewayManagedSessionsOnConnect()
    {
        final FakeOtfAcceptor otfAcceptor2 = new FakeOtfAcceptor();
        final FakeHandler handler2 = new FakeHandler(otfAcceptor2);
        try (FixLibrary library2 = FixLibrary.connect(
            acceptingLibraryConfig(handler2, ACCEPTOR_ID, INITIATOR_ID, IPC_CHANNEL)))
        {
            assertEquals(1, handler2.awaitSessionId(() -> library2.poll(1)));
        }
    }

    @Test
    public void engineAndLibraryPairsShouldBeRestartable()
    {
        messagesCanBeExchanged();

        acceptingLibrary.close();
        acceptingEngine.close();
        assertSequenceIndicesAre(0);
        clearMessages();

        launchAcceptingEngine();
        acceptingLibrary = newAcceptingLibrary(acceptingHandler);

        wireSessions();
        messagesCanBeExchanged();

        assertSequenceIndicesAre(1);
    }

    @Test
    public void enginesShouldBeRestartable()
    {
        messagesCanBeExchanged();

        acceptingEngine.close();

        assertAllMessagesHaveSequenceIndex(0);
        clearMessages();

        assertEventuallyTrue(
            "Library failed to disconnect",
            () ->
            {
                poll(acceptingLibrary, initiatingLibrary);
                return !acceptingLibrary.isConnected();
            });

        launchAcceptingEngine();

        acceptingLibrary.close();

        acceptingLibrary = newAcceptingLibrary(acceptingHandler);

        wireSessions();

        messagesCanBeExchanged();

        assertSequenceIndicesAre(1);
    }

    @Test
    public void engineShouldAcquireTimedOutLibrariesSessions()
    {
        acquireAcceptingSession();

        acceptingEngineHasSession();
    }

    @Test
    public void engineShouldAcquireClosedLibrariesSessions()
    {
        acquireAcceptingSession();
        acceptingLibrary.close();

        assertEquals(DISABLED, acceptingSession.state());

        acceptingEngineHasSession();
    }

    private void releaseSessionToEngine(
        final Session session,
        final FixLibrary library,
        final FixEngine engine)
    {
        final long connectionId = session.connectionId();

        final SessionReplyStatus status = releaseToGateway(library, session);

        assertEquals(OK, status);
        assertEquals(SessionState.DISABLED, session.state());
        assertThat(library.sessions(), hasSize(0));

        final List<SessionInfo> sessions = gatewayLibraryInfo(engine).sessions();
        assertThat(sessions, contains(hasConnectionId(connectionId)));
    }

    private void reacquireSession(
        final Session session,
        final FixLibrary library,
        final FixEngine engine,
        final long sessionId,
        final int lastReceivedMsgSeqNum,
        final int sequenceIndex,
        final SessionReplyStatus expectedStatus)
    {
        final SessionReplyStatus status = requestSession(library, sessionId, lastReceivedMsgSeqNum, sequenceIndex);
        assertEquals(expectedStatus, status);

        assertThat(gatewayLibraryInfo(engine).sessions(), hasSize(0));

        engineIsManagingSession(engine, session.id());

        assertEventuallyTrue(
            "library manages session",
            () ->
            {
                pollLibraries();
                assertContainsOnlySession(session, library);
            });
    }

    private void assertContainsOnlySession(final Session session, final FixLibrary library)
    {
        final List<Session> sessions = library.sessions();
        assertThat(sessions, hasSize(1));

        final Session newSession = sessions.get(0);
        assertTrue(newSession.isConnected());
        assertEquals(session.id(), newSession.id());
        assertEquals(session.username(), newSession.username());
        assertEquals(session.password(), newSession.password());
    }

    private void engineIsManagingSession(final FixEngine engine, final long sessionId)
    {
        final List<LibraryInfo> libraries = libraries(engine);
        assertThat(libraries.get(0).sessions(), contains(hasSessionId(sessionId)));
    }

    private void engineShouldManageSession(
        final Session session,
        final FixLibrary library,
        final FakeOtfAcceptor otfAcceptor,
        final Session otherSession,
        final FixLibrary otherLibrary,
        final FakeOtfAcceptor otherAcceptor)
    {
        final long sessionId = session.id();
        final int lastReceivedMsgSeqNum = session.lastReceivedMsgSeqNum();
        final int sequenceIndex = session.sequenceIndex();
        final List<FixMessage> messages = otfAcceptor.messages();

        releaseToGateway(library, session);

        messagesCanBeExchanged(otherSession, otherLibrary, library, otherAcceptor);

        final SessionReplyStatus status = requestSession(library, sessionId, lastReceivedMsgSeqNum, sequenceIndex);
        assertEquals(OK, status);

        messagesCanBeExchanged(otherSession, otherLibrary, library, otherAcceptor);

        // Callbacks for the missing messages whilst the gateway managed them
        final String expectedSeqNum = String.valueOf(lastReceivedMsgSeqNum + 1);
        assertEquals(1, messages
            .stream()
            .filter(msg -> msg.getMsgType().equals("1") && msg.get(MSG_SEQ_NUM).equals(expectedSeqNum))
            .count());
    }

    private void disconnectSessions()
    {
        logoutAcceptingSession();

        assertSessionsDisconnected();

        acceptingSession.close();
        initiatingSession.close();
    }

    private void logoutAcceptingSession()
    {
        assertThat(acceptingSession.startLogout(), greaterThan(0L));
    }
}
