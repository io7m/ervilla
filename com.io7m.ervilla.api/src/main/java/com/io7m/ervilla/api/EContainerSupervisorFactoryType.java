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

import java.util.Optional;

/**
 * A factory of container supervisors.
 */

public interface EContainerSupervisorFactoryType
{
  /**
   * @param configuration The configuration
   *
   * @return Information about the container backend, if containers are
   * supported on this system
   *
   * @throws InterruptedException On interruption
   */

  Optional<EContainerBackend> isSupported(
    EContainerConfiguration configuration)
    throws InterruptedException;

  /**
   * Create a container supervisor.
   *
   * @param configuration The configuration
   * @param scope         The intended supervisor scope
   *
   * @return A container supervisor
   */

  EContainerSupervisorType create(
    EContainerConfiguration configuration,
    EContainerSupervisorScope scope)
    throws Exception;
}
