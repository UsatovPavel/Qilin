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
    style refinement fill:#fff8dc,fill-opacity:1,stroke:#c79300,stroke-width:2px,color:#493500;
    style legend fill:transparent,stroke:#aaaaaa;
```

## Domain language



Тут я сам себе понятным образом должен написать

| Term | Meaning in this integration |
| --- | --- |
| Dyck language | A language of balanced, properly nested brackets, such as `()`, `[]`, and `([])`. `([)]` is not a Dyck word. |
| PAR, PAR-k, PAR-UNl | Analyses from the CFL-IDR article. |
| Mutual refinement | The baseline procedure on which the article's other refinement analyses are based. Repeated α/β analyses retain only graph edges participating in accepted paths, until the edge set stabilizes. |
| Condensation | Merge vertices whose mutual reachability is confirmed by under pairs; restore results to the original vertices after refinement. |
| UCFS | Universal Context-Free Solver: the library implementing GLL used by CFL-IDR. |
| SPPF | **Shared Packed Parse Forest**: a structure built by UCFS/GLL that stores parsing conclusions and shares their supporting structure. CFL-IDR extracts reachable pairs and contributing graph edges from it. |
| GLL | Algorithm used by UCFS to find graph paths accepted by a grammar. |
| Descriptor | A parsing step in UCFS, stored in the descriptor queue. It records the current graph vertex, grammar state, GraphStack node, and partial SPPF result. |
| GraphStructuredStack | The structure labeled GraphStack in the diagram. In UCFS, each node identifies a subtask by its nonterminal and starting graph vertex. Edges record its waiting callers, their return grammar states, and their partial SPPF results, so parsing can resume when the subtask produces a result. |

## UCFS

```mermaid
flowchart TD
    %% UCFS solver at cb2105f: Grammar.rsm, IGll.parse, Gll.handleDescriptor.
    UGrammar["Grammar"]
    UInput["Input graph"]

    subgraph uLoop["GLL loop"]
        UQueue["Descriptor queue"]
        UStep["GLL step"]
        UGss["GraphStack"]
        UForest["SPPF"]

        UQueue -->|"next descriptor"| UStep
        UStep -->|"new descriptors"| UQueue
        UStep -->|"nonterminal calls / returns"| UGss
        UGss -->|"resume waiting continuations"| UQueue
        UStep -->|"share conclusions"| UForest
        UForest -.->|"reuse partial conclusions"| UStep
    end

    UResults["SPPF roots"]

    UGrammar -->|"compile to RSM"| UStep
    UInput -->|"seed start vertices"| UQueue
    UInput -->|"match edge labels"| UStep
    UStep -->|"accepted all-grammar path"| UResults
    UForest -.->|"accepted ranges: start / end vertices"| UResults
    UQueue -.->|"no pending steps: return results"| UResults

    subgraph uLegend["Legend"]
        direction LR
        uLegendInput["Inputs"]
        uLegendSolver["UCFS"]
        uLegendResult["Results"]
    end

    classDef input fill:#e8f0fe,stroke:#4277af,color:#183249;
    classDef solver fill:#f0eafa,stroke:#8062ab,color:#35234c;
    classDef result fill:#e5f5ec,stroke:#328066,color:#183e31;
    class UGrammar,UInput,uLegendInput input;
    class UQueue,UStep,UGss,UForest,uLegendSolver solver;
    class UResults,uLegendResult result;
    style uLoop fill:#fff8dc,fill-opacity:1,stroke:#c79300,stroke-width:2px,color:#493500;
    style uLegend fill:transparent,stroke:#aaaaaa;
```
