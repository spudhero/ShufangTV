package io.github.spudhero.shufangtv.net;

import static org.junit.Assert.*;

import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import org.junit.Test;

public class SmbRetryPolicyTest {
  @Test
  public void idleClosedShareRetriesOnceWithoutTreatingAllRuntimeErrorsAsNetworkFailures()
      throws Exception {
    RuntimeException closed =
        new com.hierynomus.smbj.common.SMBRuntimeException("DiskShare has already been closed");
    NetworkFailure failure = SmbConnector.translateReadFailure(closed, false);
    assertTrue(failure.retryable);
    int[] attempts = {0};
    assertEquals(
        "fresh share",
        NetworkRetry.run(
            () -> {
              if (attempts[0]++ == 0) throw failure;
              return "fresh share";
            }));
    assertEquals(2, attempts[0]);
    assertFalse(SmbConnector.translateReadFailure(closed, true).retryable);
    assertFalse(
        SmbConnector.translateReadFailure(new IllegalStateException("local"), false).retryable);
    SMBApiException auth =
        new SMBApiException(0xc000006dL, SMB2MessageCommandCode.SMB2_SESSION_SETUP, "auth", null);
    assertFalse(SmbConnector.translateReadFailure(auth, false).retryable);
    SMBApiException path =
        new SMBApiException(0xc0000034L, SMB2MessageCommandCode.SMB2_CREATE, "path", null);
    assertFalse(SmbConnector.translateReadFailure(path, false).retryable);
  }

  @Test
  public void disconnectedTransportCanCreateOneFreshSession() throws Exception {
    NetworkFailure disconnected =
        SmbConnector.translate(new TransportException(new SocketException("reset")));
    assertTrue(disconnected.retryable);
    int[] attempts = {0};
    String value =
        NetworkRetry.run(
            () -> {
              if (attempts[0]++ == 0) throw disconnected;
              return "fresh";
            });
    assertEquals("fresh", value);
    assertEquals(2, attempts[0]);
  }

  @Test
  public void credentialsAndPathsAreNotRetriedEvenWhenWrapped() {
    SMBApiException auth =
        new SMBApiException(0xc000006dL, SMB2MessageCommandCode.SMB2_SESSION_SETUP, "auth", null);
    assertFalse(SmbConnector.translate(new TransportException(auth)).retryable);
    assertFalse(
        SmbConnector.translate(
                new com.hierynomus.smbj.common.SMBRuntimeException(new TransportException(auth)))
            .retryable);
    SMBApiException path =
        new SMBApiException(0xc0000034L, SMB2MessageCommandCode.SMB2_CREATE, "path", null);
    assertFalse(SmbConnector.translate(path).retryable);
    SMBApiException disconnected =
        new SMBApiException(0xc000020cL, SMB2MessageCommandCode.SMB2_READ, "closed", null);
    assertTrue(SmbConnector.translate(disconnected).retryable);
  }

  @Test
  public void cancellationAndInvalidHostAreNotRetried() {
    assertFalse(
        SmbConnector.translate(new TransportException(new InterruptedIOException("cancelled")))
            .retryable);
    assertFalse(
        SmbConnector.translate(new TransportException(new UnknownHostException("invalid")))
            .retryable);
    assertFalse(SmbConnector.translate(new IOException("local write failed")).retryable);
  }
}
