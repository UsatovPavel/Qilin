```mermaid
flowchart TD
    %% Standard cfl-idr-with-gll PAR pipeline; no factored or INT backend.
    A["Input graph"]
    B["Dyck GLL"]
    C["Condensation"]
    subgraph refinement["CFL-IDR loop"]
        D["α GLL"]
        E["β GLL"]
        F{"Stable?"}
        D -->|"SPPF: retain edges; PAR β"| E
        E -->|"SPPF: retain edges"| F
        F -->|"mutual refinement: graph changed"| D
    end
    G["Under / Over"]

    A -->|"Dyck grammar"| B
    A -->|"original graph"| C
    B -->|"Guaranteed reachable pairs"| C
    C -->|"PAR α"| D
    F -->|"stable / empty: over pairs"| G
    B -->|"Guaranteed reachable pairs"| G
    C -.->|"restore original vertices"| G

    subgraph legend["Legend"]
        direction LR
        legendCflidr["CFL-IDR"]
        legendUcfs["UCFS"]
    end

    classDef library fill:#e5f5ec,stroke:#328066,color:#183e31;
    classDef solver fill:#f0eafa,stroke:#8062ab,color:#35234c;
    class A,C,F,G,legendCflidr library;
    class B,D,E,legendUcfs solver;
    style refinement fill:#ffe066,fill-opacity:1,stroke:#c79300,stroke-width:2px,color:#493500;
    style legend fill:transparent,stroke:#aaaaaa;
```

## Domain language



Тут я сам себе понятным образом должен написать

| Term                               | Meaning in this integration                                                                                                                                                                                    |
| ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SPPF                               | **Shared Packed Parse Forest**: a structure built by UCFS/GLL that shares common parts of path derivations. CFL-IDR extracts reachable pairs and contributing graph edges from it.                             |
| GLL                                | **A**lgorithm used by UCFS to find graph paths accepted by a grammar.                                                                                                                                          |
| Dyck language                      | A language of balanced, properly nested brackets, such as `()`, `[]`, and `([])`. `([)]` is not a Dyck word                                                                                                    |
| Interleaved Dyck                   | Each bracket family must be balanced independently; nesting between families is unrestricted. For example, `([)]` is allowed. In this integration, α represents calls/returns and β represents field accesses. |
| PAR                                | The parity approximation: check one bracket family exactly and require an even number of symbols from the other family; then exchange their roles.                                                             |
| Mutual refinement                  | Repeated α/β analyses that retain only graph edges participating in accepted paths, until the edge set stabilizes.                                                                                             |
| Guaranteed reachable pairs (under) | Pairs confirmed by a Dyck parse over the combined α/β alphabet. They are valid IDR pairs, but some valid pairs may be absent.                                                                                  |
| Possibly reachable pairs (over)    | The remaining candidate pairs after refinement. They include all valid IDR pairs and may include false positives.                                                                                              |
| Condensation                       | Merge vertices whose mutual reachability is confirmed by under pairs; restore results to the original vertices after refinement.                                                                               |
