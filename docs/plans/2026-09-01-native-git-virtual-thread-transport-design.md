# Native Git Virtual Thread Transport Design

## Context

The blocking Git parser can run on virtual threads through
`BufferedByteInput` and `BufferedByteOutput`. HTTP currently reaches that parser
through Jetty servlet streams, while the native TCP Git transport service is a
disabled placeholder.

For a small, testable step, make the native Git TCP transport the first
production adapter for the blocking parser. Keep Jetty unchanged until the
native socket path proves the transport contract.

The startup output also includes Apache SSHD's INFO line for
`DefaultIoServiceFactoryFactory`. That line is not useful for identifying the
native Git listener and should be hidden from normal logs without hiding SSHD
warnings or errors.

## Design

`GitNativeTransportService` will read `GitTransportConfig`. If disabled, it will
not bind a socket. If enabled, it will bind the configured address and port,
including port `0` in tests, and expose the actual bound port for diagnostics
and tests.

The service will run one accept loop thread per listening socket. Each accepted
connection will be handled by a new virtual thread. The connection handler will
wrap the socket input and output streams with the existing blocking byte
adapters, build a `GitBlockingWireTransport`, and drive `GitBlockingWireSession`
for the Git command request.

Backpressure is represented by blocking writes. On a virtual thread, that parks
the connection handler without consuming a platform thread. Connection failures
close only the affected socket. Service stop closes the listener and lets the
accept loop exit.

Startup logging will include an Orion-owned native Git listener message with
the actual bound address and port. The Apache SSHD
`DefaultIoServiceFactoryFactory` logger will be configured at WARN in
`LogInitializer` so its INFO startup line is not printed.

## Tests

Unit-style tests in `net/git-transport` will cover disabled startup, binding to
loopback port `0`, exposing the actual bound port, stopping the listener, and
running accepted connections on virtual threads.

`LogInitializerTest` will cover that
`org.apache.sshd.common.io.DefaultIoServiceFactoryFactory` is configured at
WARN.
