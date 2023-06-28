/*
 * Copyright © 2023 Mark Raynsford <code@io7m.com> https://www.io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */


package com.io7m.ervilla.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.Objects;

/**
 * A ready check that succeeds if a byte can be read from a TCP socket.
 */

public final class EReadyCheckTCPSocketRead implements EReadyCheckType
{
  private static final Logger LOG =
    LoggerFactory.getLogger(EReadyCheckTCPSocketRead.class);

  private final String address;
  private final int port;

  /**
   * A ready check that succeeds if a TCP can be successfully connected to.
   *
   * @param inAddress The address
   * @param inPort    The port
   */

  public EReadyCheckTCPSocketRead(
    final String inAddress,
    final int inPort)
  {
    this.address =
      Objects.requireNonNull(inAddress, "address");
    this.port = inPort;
  }

  @Override
  public boolean isReady()
    throws Exception
  {
    try (var socket = new Socket(this.address, this.port)) {
      socket.setSoTimeout(1000);
      final var r = socket.getInputStream().read();
      LOG.debug("Read: 0x{}", Integer.toUnsignedString(r, 16));
      return r != -1;
    }
  }
}
