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

package com.io7m.ervilla.native_exec.internal;

import com.io7m.ervilla.api.EContainerConfiguration;
import com.io7m.ervilla.api.EContainerFactoryType;
import com.io7m.ervilla.api.EContainerReference;
import com.io7m.ervilla.api.EContainerSpec;
import com.io7m.ervilla.api.EContainerSupervisorScope;
import com.io7m.ervilla.api.EContainerSupervisorType;
import com.io7m.ervilla.api.EContainerType;
import com.io7m.ervilla.api.EPortPublish;
import com.io7m.ervilla.api.EVolumeMount;
import com.io7m.jdeferthrow.core.ExceptionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A container supervisor.
 */

public final class EContainerSupervisor implements EContainerSupervisorType
{
  private static final Logger LOG =
    LoggerFactory.getLogger(EContainerSupervisor.class);

  private static final Consumer<String> IGNORING =
    text -> {
    };

  private final EContainerConfiguration configuration;
  private final EContainerStore store;
  private final Consumer<EContainerSupervisor> onClose;
  private final HashMap<String, EContainer> containers;
  private final HashMap<String, EPod> pods;
  private final ExecutorService ioSupervisor;
  private final UUID id;
  private final EContainerSupervisorScope scope;
  private final SecureRandom rng;

  /**
   * A container supervisor.
   *
   * @param inConfiguration The container configuration
   * @param inStore         The container store
   * @param inOnClose       The on-close function
   * @param inId            The supervisor ID
   * @param inScope         The supervisor scope
   */

  private EContainerSupervisor(
    final EContainerConfiguration inConfiguration,
    final EContainerStore inStore,
    final ExecutorService inExecutor,
    final Consumer<EContainerSupervisor> inOnClose,
    final UUID inId,
    final EContainerSupervisorScope inScope)
  {
    this.configuration =
      Objects.requireNonNull(inConfiguration, "configuration");
    this.store =
      Objects.requireNonNull(inStore, "store");
    this.onClose =
      Objects.requireNonNull(inOnClose, "onClose");
    this.ioSupervisor =
      Objects.requireNonNull(inExecutor, "inExecutor");
    this.id =
      Objects.requireNonNull(inId, "id");
    this.scope =
      Objects.requireNonNull(inScope, "scope");

    this.containers =
      new HashMap<>();
    this.pods =
      new HashMap<>();

    try {
      this.rng = SecureRandom.getInstanceStrong();
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Create a new supervisor.
   *
   * @param inConfiguration The configuration
   * @param inStore         The container store
   * @param inExecutor      An executor for I/O operations
   * @param onClose         The on-close function
   * @param id              The supervisors instance ID
   * @param scope           The supervisor scope
   *
   * @return A new supervisor
   */

  public static EContainerSupervisor create(
    final EContainerConfiguration inConfiguration,
    final EContainerStore inStore,
    final ExecutorService inExecutor,
    final Consumer<EContainerSupervisor> onClose,
    final UUID id,
    final EContainerSupervisorScope scope)
  {
    return new EContainerSupervisor(
      inConfiguration,
      inStore,
      inExecutor,
      onClose,
      id,
      scope
    );
  }

  /**
   * Clean up all containers and pods that are in the current store.
   *
   * @throws Exception On errors
   */

  public void cleanUpOldContainersAndPods()
    throws Exception
  {
    final var exceptions = new ExceptionTracker<Exception>();

    final var containerNames = this.store.containerList();
    LOG.debug(
      "Cleaning up {} old containerNames.",
      Integer.valueOf(containerNames.size())
    );

    for (final var container : containerNames) {
      try {
        this.executeContainerStop(container.name());
        this.executeContainerRemove(container.name());
        this.store.containerDelete(
          new EContainerReference(container.name(), Optional.empty())
        );
      } catch (final Exception e) {
        exceptions.addException(e);
      }
    }

    final var podNames = this.store.podList();
    LOG.debug(
      "Cleaning up {} old podNames.",
      Integer.valueOf(podNames.size())
    );

    for (final var pod : podNames) {
      try {
        this.executePodDeletion(pod);
        this.store.podDelete(pod);
      } catch (final Exception e) {
        exceptions.addException(e);
      }
    }

    exceptions.throwIfNecessary();
  }

  private static String portSpec(
    final EPortPublish publish)
  {
    if (publish.hostIP().isPresent()) {
      final var hostIP = publish.hostIP().get();
      return "%s:%s:%s/%s".formatted(
        hostIP,
        Integer.valueOf(publish.hostPort()),
        Integer.valueOf(publish.containerPort()),
        switch (publish.protocol()) {
          case TCP -> "tcp";
          case UDP -> "udp";
        }
      );
    }
    return "%s:%s/%s".formatted(
      Integer.valueOf(publish.hostPort()),
      Integer.valueOf(publish.containerPort()),
      switch (publish.protocol()) {
        case TCP -> "tcp";
        case UDP -> "udp";
      }
    );
  }

  private static String volumeSpec(
    final EVolumeMount mount)
  {
    return String.format(
      "%s:%s",
      mount.hostPath().toAbsolutePath(),
      mount.containerPath()
    );
  }

  private String freshToken()
  {
    final var data = new byte[9];
    this.rng.nextBytes(data);
    return Base64.getMimeEncoder()
      .encodeToString(data)
      .replace('/', '_')
      .replace('=', '_')
      .replace('+', '_')
      .toUpperCase(Locale.ROOT);
  }

  @Override
  public void close()
    throws Exception
  {
    try {
      MDC.put("Container", "*");
      MDC.put("PID", "*");
      MDC.put("Source", "supervisor");

      final var exceptions =
        new ExceptionTracker<Exception>();

      LOG.debug("Shutting down pods.");
      for (final var entry : this.pods.entrySet()) {
        try {
          final var pod = entry.getValue();
          pod.close();
        } catch (final Exception e) {
          exceptions.addException(e);
        }
      }

      LOG.debug("Shutting down containers.");
      for (final var entry : this.containers.entrySet()) {
        try {
          final var container = entry.getValue();
          container.close();
        } catch (final Exception e) {
          exceptions.addException(e);
        }
      }

      LOG.debug("Deleting pod records.");
      for (final var entry : this.pods.entrySet()) {
        try {
          final var pod = entry.getValue();
          this.store.podDelete(pod.name);
        } catch (final Exception e) {
          exceptions.addException(e);
        }
      }

      this.store.close();
      exceptions.throwIfNecessary();
    } finally {
      MDC.remove("Container");
      MDC.remove("PID");
      MDC.remove("Source");

      this.onClose.accept(this);
    }
  }

  @Override
  public EContainerType start(
    final EContainerSpec spec)
    throws IOException, InterruptedException
  {
    return this.createAndStartContainer(Optional.empty(), spec);
  }

  private EContainer createAndStartContainer(
    final Optional<String> pod,
    final EContainerSpec spec)
    throws IOException, InterruptedException
  {
    try {
      MDC.put("Container", "*");
      MDC.put("PID", "*");
      MDC.put("Source", "supervisor");

      final var uniqueName =
        this.createFreshContainerName();

      this.store.containerPut(
        new EContainerReference(uniqueName, pod)
      );

      final var arguments = new ArrayList<String>();
      arguments.add(this.configuration.podmanExecutable());
      arguments.add("run");
      arguments.add("--interactive");
      arguments.add("--tty");

      for (final var entry : new TreeMap<>(spec.environment()).entrySet()) {
        arguments.add("--env");
        arguments.add("%s=%s".formatted(entry.getKey(), entry.getValue()));
      }

      for (final var mount : spec.volumeMounts()) {
        arguments.add("--volume");
        arguments.add(volumeSpec(mount));
      }

      /*
       * Join the container to the pod, if one is specified. Note that this
       * is mutually exclusive with having published ports; the ports must
       * be published on the pod.
       */

      pod.ifPresentOrElse(podName -> {
        arguments.add("--pod");
        arguments.add(podName);
      }, () -> {
        for (final var port : spec.ports()) {
          arguments.add("--publish");
          arguments.add(portSpec(port));
        }
      });

      arguments.add("--name");
      arguments.add(uniqueName);
      arguments.add(spec.fullImageName());
      arguments.addAll(spec.arguments());

      final var process =
        this.executeLogged(
          Optional.of(uniqueName),
          "podman",
          arguments,
          IGNORING
        );

      MDC.put("Container", uniqueName);
      MDC.put("PID", Long.toUnsignedString(process.pid()));

      final var container =
        new EContainer(this, this.configuration, spec, uniqueName, process);

      this.containers.put(uniqueName, container);
      container.waitUntilReady();

      LOG.debug("Container appears to be running.");
      return container;
    } finally {
      MDC.remove("Container");
      MDC.remove("PID");
      MDC.remove("Source");
    }
  }

  private String createFreshContainerName()
  {
    return "ERVILLA-%s-%s".formatted(
      this.configuration.projectName(),
      this.freshToken()
    );
  }

  private void superviseProcessOutput(
    final Optional<String> container,
    final String command,
    final Process process,
    final Consumer<String> receiver)
  {
    this.ioSupervisor.execute(() -> {
      MDC.put("PID", Long.toUnsignedString(process.pid()));
      container.ifPresent(s -> MDC.put("Container", s));
      MDC.put("Source", command + ": stdout");

      try (var reader = process.inputReader()) {
        while (true) {
          final var line = reader.readLine();
          if (line == null) {
            break;
          }
          LOG.trace("{}", line);
          receiver.accept(line);
        }
      } catch (final Exception e) {
        LOG.trace("", e);
      }
    });
  }

  private void superviseProcessError(
    final Optional<String> container,
    final String command,
    final Process process)
  {
    this.ioSupervisor.execute(() -> {
      MDC.put("PID", Long.toUnsignedString(process.pid()));
      container.ifPresent(s -> MDC.put("Container", s));
      MDC.put("Source", command + ": stderr");

      try (var reader = process.errorReader()) {
        while (true) {
          final var line = reader.readLine();
          if (line == null) {
            break;
          }
          LOG.error("{}", line);
        }
      } catch (final Exception e) {
        LOG.error("", e);
      }
    });
  }

  private Process executeLogged(
    final Optional<String> container,
    final String name,
    final List<String> command,
    final Consumer<String> lineConsumer)
    throws IOException
  {
    LOG.debug("Exec: {}", command);

    final var process =
      new ProcessBuilder(command)
        .start();

    this.superviseProcessError(container, name, process);
    this.superviseProcessOutput(container, name, process, lineConsumer);
    return process;
  }

  @Override
  public EContainerFactoryType createPod(
    final List<EPortPublish> ports)
    throws IOException, InterruptedException
  {
    final var podName =
      this.createFreshPodName();
    final var pod =
      new EPod(podName, this);

    this.store.podPut(podName);

    final var createArgs = new ArrayList<String>();
    createArgs.add(this.configuration.podmanExecutable());
    createArgs.add("pod");
    createArgs.add("create");
    for (final var port : ports) {
      createArgs.add("--publish");
      createArgs.add(portSpec(port));
    }
    createArgs.add("--name");
    createArgs.add(podName);

    final var createProc =
      this.executeLogged(
        Optional.of(podName),
        "pod-create",
        createArgs,
        IGNORING
      );

    final int exitCode =
      createProc.waitFor();

    if (exitCode != 0) {
      throw new IOException("Could not create pod.");
    }

    this.pods.put(podName, pod);
    return pod;
  }

  private String createFreshPodName()
  {
    return "ERVILLA-POD-%s-%s".formatted(
      this.configuration.projectName(),
      this.freshToken()
    );
  }

  private void executeContainerRemove(
    final String name)
    throws IOException, InterruptedException
  {
    final var rmArgs = new ArrayList<String>(6);
    rmArgs.add(this.configuration.podmanExecutable());
    rmArgs.add("rm");
    rmArgs.add("-f");
    rmArgs.add("--volumes");
    rmArgs.add("--ignore");
    rmArgs.add(name);

    final var rmProc =
      this.executeLogged(
        Optional.of(name),
        "rm",
        rmArgs,
        IGNORING
      );

    try {
      MDC.pushByKey("PID", Long.toUnsignedString(rmProc.pid()));
      LOG.debug("Waiting for 'rm' invocation.");
      rmProc.waitFor(3L, TimeUnit.SECONDS);
      LOG.debug("Status {}", rmProc);
    } finally {
      MDC.popByKey("PID");
    }
  }

  private void executeContainerStop(
    final String name)
    throws IOException, InterruptedException
  {
    final var closeArgs = new ArrayList<String>(6);
    closeArgs.add(this.configuration.podmanExecutable());
    closeArgs.add("stop");
    closeArgs.add("--ignore");
    closeArgs.add("--time");
    closeArgs.add("1");
    closeArgs.add(name);

    final var stopProc =
      this.executeLogged(
        Optional.of(name),
        "close",
        closeArgs,
        IGNORING
      );

    try {
      MDC.pushByKey("PID", Long.toUnsignedString(stopProc.pid()));
      LOG.debug("Waiting for 'close' invocation.");
      stopProc.waitFor(3L, TimeUnit.SECONDS);
      LOG.debug("Status {}", stopProc);
    } finally {
      MDC.popByKey("PID");
    }
  }

  private void executePodDeletion(
    final String name)
    throws IOException, InterruptedException
  {
    final var rmArgs = new ArrayList<String>();
    rmArgs.add(this.configuration.podmanExecutable());
    rmArgs.add("pod");
    rmArgs.add("rm");
    rmArgs.add("-f");
    rmArgs.add(name);

    final var rmProc =
      this.executeLogged(
        Optional.of(name),
        "pod-rm",
        rmArgs,
        IGNORING
      );

    final int exitCode = rmProc.waitFor();
    if (exitCode != 0) {
      throw new IOException("Could not remove pod.");
    }
  }

  private static final class EPod
    implements EContainerFactoryType, AutoCloseable
  {
    private final String name;
    private final EContainerSupervisor supervisor;

    EPod(
      final String inName,
      final EContainerSupervisor inSupervisor)
    {
      this.name =
        Objects.requireNonNull(inName, "name");
      this.supervisor =
        Objects.requireNonNull(inSupervisor, "supervisor");
    }

    @Override
    public EContainerType start(
      final EContainerSpec spec)
      throws IOException, InterruptedException
    {
      return this.supervisor.createAndStartContainer(
        Optional.of(this.name),
        spec
      );
    }

    @Override
    public void close()
      throws IOException, InterruptedException
    {
      this.supervisor.executePodDeletion(this.name);
    }
  }

  private static final class EContainer
    implements EContainerType
  {
    private final String name;
    private final EContainerSupervisor supervisor;
    private final EContainerConfiguration configuration;
    private final EContainerSpec spec;
    private volatile Process process;

    private EContainer(
      final EContainerSupervisor inSupervisor,
      final EContainerConfiguration inConfiguration,
      final EContainerSpec inSpec,
      final String inName,
      final Process inProcess)
    {
      this.supervisor =
        Objects.requireNonNull(inSupervisor, "supervisor");
      this.configuration =
        Objects.requireNonNull(inConfiguration, "configuration");
      this.spec =
        Objects.requireNonNull(inSpec, "spec");
      this.name =
        Objects.requireNonNull(inName, "name");
      this.process =
        Objects.requireNonNull(inProcess, "process");
    }

    private static void mdcPop()
    {
      MDC.remove("Container");
      MDC.remove("PID");
      MDC.remove("Source");
    }

    /**
     * Wait until this container is fully ready.
     */

    void waitUntilReady()
      throws InterruptedException, IOException
    {
      final var completionLatch =
        new CountDownLatch(1);
      final var existing =
        MDC.getCopyOfContextMap();

      final var thread = new Thread(() -> {
        try {
          MDC.setContextMap(existing);
          this.runLivenessCheck();
          this.runReadyCheck();
          completionLatch.countDown();
        } catch (final Exception e) {
          LOG.error("Failed waiting for container: ", e);
        }
      });
      thread.setDaemon(true);
      thread.setName("com.io7m.ervilla.native_exec.await");
      thread.start();

      try {
        final var completed =
          completionLatch.await(
            this.configuration.startupWaitTime(),
            this.configuration.startupWaitTimeUnit()
          );

        if (!completed) {
          throw new IOException("Timed out waiting for container to start.");
        }
      } finally {
        thread.interrupt();
      }
    }

    /**
     * Run the container "up" check.
     */

    void runLivenessCheck()
      throws IOException, InterruptedException
    {
      final var psArgs = new ArrayList<String>();
      psArgs.add(this.configuration.podmanExecutable());
      psArgs.add("ps");
      psArgs.add("--filter");
      psArgs.add("name=%s".formatted(this.name));
      psArgs.add("--format");
      psArgs.add("{{.Status}}");

      while (this.process.isAlive()) {
        final var statusText =
          new AtomicReference<String>();

        final var statusProc =
          this.supervisor.executeLogged(
            Optional.of(this.name),
            "status",
            psArgs,
            statusText::set
          );

        final int exitCode =
          statusProc.waitFor();

        if (exitCode == 0) {
          final var status = statusText.get();
          if (status != null) {
            if (status.toUpperCase().startsWith("UP ")) {
              break;
            }
          }
        }
      }
    }

    /**
     * Run the image-specific ready check; the container might be "up", but
     * the application within it might not be ready yet.
     */

    void runReadyCheck()
      throws InterruptedException
    {
      final var readyCheck = this.spec.readyCheck();
      while (this.process.isAlive()) {
        try {
          if (readyCheck.isReady()) {
            LOG.debug("Ready check returned true.");
            break;
          }
          Thread.sleep(100L);
        } catch (final InterruptedException e) {
          throw e;
        } catch (final Exception e) {
          LOG.trace("Ready check failed: ", e);
        }
      }
    }

    @Override
    public String name()
    {
      return this.name;
    }

    @Override
    public int executeAndWait(
      final List<String> command,
      final long time,
      final TimeUnit unit)
      throws InterruptedException, IOException
    {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(unit, "unit");

      try {
        this.mdcPush();

        final var execArgs = new ArrayList<String>();
        execArgs.add(this.configuration.podmanExecutable());
        execArgs.add("exec");
        execArgs.add(this.name);
        execArgs.addAll(command);

        final var execProcess =
          this.supervisor.executeLogged(
            Optional.of(this.name),
            command.get(0),
            execArgs,
            IGNORING
          );

        execProcess.waitFor(time, unit);
        return execProcess.exitValue();
      } finally {
        mdcPop();
      }
    }

    @Override
    public int executeAndWaitIndefinitely(
      final List<String> command)
      throws IOException, InterruptedException
    {
      return this.executeAndWait(command, Long.MAX_VALUE, TimeUnit.SECONDS);
    }

    @Override
    public void copyInto(
      final Path source,
      final String destination)
      throws InterruptedException, IOException
    {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(destination, "destination");

      try {
        this.mdcPush();

        final var copyArgs = new ArrayList<String>();
        copyArgs.add(this.configuration.podmanExecutable());
        copyArgs.add("cp");
        copyArgs.add(source.toAbsolutePath().toString());
        copyArgs.add("%s:%s".formatted(this.name, destination));

        final var copyProcess =
          this.supervisor.executeLogged(
            Optional.of(this.name),
            "cp",
            copyArgs,
            IGNORING
          );

        copyProcess.waitFor();
        if (copyProcess.exitValue() != 0) {
          throw new IOException(
            "Process '%s' returned exit code %d"
              .formatted(
                copyArgs,
                Integer.valueOf(copyProcess.exitValue())
              )
          );
        }
      } finally {
        mdcPop();
      }
    }

    @Override
    public void copyFrom(
      final String source,
      final Path destination)
      throws InterruptedException, IOException
    {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(destination, "destination");

      try {
        this.mdcPush();

        final var copyArgs = new ArrayList<String>();
        copyArgs.add(this.configuration.podmanExecutable());
        copyArgs.add("cp");
        copyArgs.add("%s:%s".formatted(this.name, source));
        copyArgs.add(destination.toAbsolutePath().toString());

        final var copyProcess =
          this.supervisor.executeLogged(
            Optional.of(this.name),
            "cp",
            copyArgs,
            IGNORING
          );

        copyProcess.waitFor();
        if (copyProcess.exitValue() != 0) {
          throw new IOException(
            "Process '%s' returned exit code %d"
              .formatted(
                copyArgs,
                Integer.valueOf(copyProcess.exitValue())
              )
          );
        }
      } finally {
        mdcPop();
      }
    }

    private void mdcPush()
    {
      MDC.put("Container", this.name);
      MDC.put("PID", Long.toUnsignedString(this.process.pid()));
      MDC.put("Source", "supervisor");
    }

    @Override
    public void stop()
      throws InterruptedException, IOException
    {
      try {
        this.mdcPush();

        this.supervisor.executeContainerStop(this.name);
        this.process.waitFor(5L, TimeUnit.SECONDS);
        if (this.process.isAlive()) {
          throw new IOException(
            "Container process %s is still alive!".formatted(this.process)
          );
        }

        this.deleteContainerRecord();
      } finally {
        mdcPop();
      }
    }

    private void deleteContainerRecord()
      throws IOException
    {
      LOG.debug("Deleting container record.");
      this.supervisor.store.containerDelete(
        new EContainerReference(this.name, Optional.empty())
      );
    }

    @Override
    public void start()
      throws IOException, InterruptedException
    {
      try {
        this.mdcPush();

        final var startArgs = new ArrayList<String>();
        startArgs.add(this.configuration.podmanExecutable());
        startArgs.add("start");
        startArgs.add("--interactive");
        startArgs.add("--attach");
        startArgs.add(this.name);

        this.process = this.supervisor.executeLogged(
          Optional.of(this.name),
          "start",
          startArgs,
          IGNORING
        );

        this.waitUntilReady();
      } finally {
        mdcPop();
      }
    }

    @Override
    public void close()
      throws Exception
    {
      try {
        this.mdcPush();

        /*
         * Podman 4.* and above supports removing (with force) in one operation.
         * Older versions require stopping and then removing.
         */

        LOG.debug("Shutting down.");
        this.supervisor.executeContainerStop(this.name);
        this.supervisor.executeContainerRemove(this.name);

        LOG.debug("Waiting for container shutdown.");
        this.process.waitFor(10L, TimeUnit.SECONDS);
        LOG.debug("Status {}", this.process);
        if (this.process.isAlive()) {
          LOG.warn("Container appears to still be running!");
        }

        this.deleteContainerRecord();
      } finally {
        mdcPop();
      }
    }
  }
}
