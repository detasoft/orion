```mermaid
sequenceDiagram
    participant Client
    participant Server
    Client->>Server: ClientHello
    Server->>Client: HelloVerifyRequest
    Client->>Server: ClientHello + cookie
    Server->>Client: ServerHello + Certificate + Key Exchange
    Client->>Server: Key Exchange + Finished
    Server->>Client: Finished
```
hello