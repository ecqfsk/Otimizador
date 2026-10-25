# Otimizador

Plugin de **diagnóstico, controle de recursos e otimização adaptativa de memória**
para servidores **Paper 26.2** (Java 25), escrito em **Kotlin** e construído com **Maven**.

O objetivo é simples: reduzir desperdício de RAM com segurança e ajudar a encontrar a
**causa real** do consumo — separando sempre o que é fato do que é hipótese.

## Princípios

- **Nunca** chama `System.gc()`, **nunca** reinicia o servidor, **nunca** apaga dados do mundo.
- Nada destrutivo acontece sem autorização explícita no `config.yml` (padrão: desligado).
- Toda métrica é apresentada com **valor, unidade e horário**; toda análise declara suas **limitações**.
- Históricos têm **teto rígido** (buffers circulares fixos): o próprio plugin não pode
  virar fonte de consumo de memória.
- "Crescimento de RAM" é tratado como **sinal**, nunca como prova de vazamento — sem
  profiler externo não há como atribuir consumo a classe ou plugin.

## Módulos

| Módulo | O que faz |
|---|---|
| `memory` | Heap usado/comprometido/máximo, não-heap, GC (overhead, frequência, pausas estimadas), RSS/nativo, threads, buffers NIO, séries temporais e tendências. |
| `chunks` | Amostragem de chunks carregados por mundo, chunks sem jogador próximo, área de spawn. Descarga (`unloadChunkRequest`) **só** com autorização, com guardas de distância/entidades/máximo por ciclo. |
| `entidades` | Contagem de entidades, itens no chão, block entities e concentração por tipo. Remoção **só** com autorização, com idade mínima, tetos e materiais protegidos. |
| `controle adaptativo` | Máquina de estados Normal → Atenção → Pressão elevada → Pressão crítica, com histerese, persistência (subida/queda) e rate-limit de alertas. Decide, não executa sozinho. |
| `jvm` | Leitura da JVM (versão, `-Xmx`, coletores, flags, NMT) e recomendações **textuais** para o script de start. |
| `diagnóstico` | Motor de achados com evidências, horários e recomendações; aponta quando a API pública não basta. |
| `relatórios` | Relatório completo em texto (`reports/`) com rotação rígida e seção de limitações. |
| `integração` | Webhook opcional (HTTPS) para alertas fora do servidor. |

## Comandos

Todos exigem permissão (node dedicado; `otimizador.admin` concede tudo).

| Comando | O que faz | Permissão |
|---|---|---|
| `/otimizador status` | Resumo de memória, estado adaptativo e recomendações | `otimizador.status` |
| `/otimizador memory` | Heap, pools, GC, RSS, threads, tendências | `otimizador.memory` |
| `/otimizador diagnose` | Diagnóstico com evidências e recomendações | `otimizador.diagnose` |
| `/otimizador chunks` | Chunks por mundo, ociosos e retenção | `otimizador.chunks` |
| `/otimizador entities` | Entidades, itens no chão, block entities | `otimizador.entities` |
| `/otimizador report` | Gera relatório completo em arquivo | `otimizador.report` |
| `/otimizador optimize` | Executa apenas as otimizações **autorizadas** | `otimizador.optimize` |
| `/otimizador modules` | Módulos ativos e limites vigentes | `otimizador.modules` |
| `/otimizador reload` | Recarrega o `config.yml` (validado; erros mantêm a config anterior) | `otimizador.reload` |
| `/otimizador help` | Ajuda | `otimizador.help` |

## Build

Requisitos: **JDK 25** e **Maven 3.9+** (sem Gradle).

```bash
mvn -B test      # 128 testes (lógica, classificação, históricos, config, controle)
mvn -B package   # gera target/Otimizador-1.0.0.jar
```

Com o toolchain local usado neste ambiente (sem instalar nada no sistema):

```bash
export JAVA_HOME=$HOME/devtools/jdk-25.0.5+8
cd "/home/miguel/Documentos/Projeto Padrão"
$HOME/devtools/apache-maven-3.10.0/bin/mvn -B package
```

Dependências principais: `paper-api 26.2.build.135-stable` (escopo `provided`), Kotlin
`2.4.21` (`jvmTarget 25`), JUnit Jupiter `5.14.4` + `junit-platform-launcher 1.14.4`
(alinhados para o Surefire descobrir os testes).

## Instalação

1. Copie `target/Otimizador-1.0.0.jar` para a pasta `plugins/` do servidor.
2. Inicie o servidor. Na primeira inicialização o Paper baixa o Kotlin stdlib do Maven
   Central (declarado em `libraries:` no `plugin.yml`) — é necessária internet 1×.
3. O `config.yml` é criado em `plugins/Otimizador/` **apenas se não existir** e nunca é
   sobrescrito em atualizações.

## Configuração

O arquivo é propositalmente curto: tudo que não é ajustado com frequência vive como
padrão no código (`Settings`). Valores inválidos caem no padrão e geram aviso no console.

```yaml
coleta:
  intervalo-memoria-segundos: 5     # 1..300
  intervalo-mundo-segundos: 10      # 5..600 (thread principal)

memoria:
  alertas:
    percentual-heap-atencao: 70
    percentual-heap-alta: 85
    percentual-heap-critico: 95
    overhead-gc-atencao-percent: 5
    overhead-gc-alta-percent: 10
    overhead-gc-critico-percent: 25

controle-adaptativo:
  habilitado: true
  histerese-percentual: 5           # pontos abaixo do limiar para sair do estado
  persistencia-subida-segundos: 30
  persistencia-queda-segundos: 60

chunks:
  desativacao:
    habilitado: false               # SEGURANÇA: descarga de chunks

entidades:
  itens-no-chao:
    remocao:
      habilitado: false             # SEGURANÇA: remoção automática
  mobs:
    remocao:
      habilitado: false             # SEGURANÇA: remoção automática
```

## Benchmark antes/depois

Compare sempre **na mesma máquina, mesmo mundo, mesmos plugins e mesma carga de jogadores**.
Uma comparação com condições diferentes não prova nada.

### Procedimento

1. **Condições fixas:** anote versão do servidor/JVM, flags (`-Xms/-Xmx`), lista de plugins
   e número de jogadores. Não troque nada entre as duas medições.
2. **Período de aquecimento:** deixe o servidor rodar **no mínimo 30–60 min** antes de
   começar a contar (o heap sobe naturalmente no início).
3. **Linha de base (antes):** com o Otimizador instalado mas **sem** mudanças de
   configuração, colete por 30 min:
   - `/otimizador status` a cada 5 min (anote horário);
   - `/otimizador report` ao final;
   - externamente: `jcmd <pid> GC.heap_info` e `ps -o rss= -p <pid>`.
4. **Intervenção:** aplique UMA mudança por vez (ex.: desligar plugin, limitar chunks,
   ajustar flags). Para mudanças de flag de JVM, o servidor precisa reiniciar — comece
   sempre do estado frio.
5. **Linha de fim (depois):** repita os mesmos passos, nas mesmas janelas de tempo.
6. **Compare os números** da tabela abaixo, não impressões.

### O que comparar (e o que NÃO confundir)

| Métrica | Como obter | O que observar |
|---|---|---|
| Heap **usado** | `/otimizador memory` | Só faz sentido junto com o piso pós-GC |
| Heap **comprometido** | `/otimizador memory` | Reservado pela JVM; pode estar ocioso |
| Heap **máximo** (`-Xmx`) | `/otimizador status` | Limite configurado, **não** é consumo físico |
| **Piso pós-GC** (`collectionUsage`) | `/otimizador memory`, série do relatório | **Esta** é a métrica que revela crescimento real |
| **RSS** (memória residente) | `/proc/self/status` (VmRSS) | Inclui pilhas, code cache, metaspace, NIO, GC |
| **Não-heap** | `/otimizador memory` | Metaspace/CodeCache/diretos |
| **Overhead de GC** | `/otimizador memory` | % de tempo em GC na janela medida |
| **Tendência** (MiB/h, R²) | `/otimizador diagnose`, `/otimizador report` | Só interpretar com amostras suficientes |

Interpretação correta:

- Heap usado oscilar **não é** vazamento; o que importa é o **piso pós-GC subindo** de
  forma linear e sustentada (o relatório mostra taxa em MiB/h e R²).
- RSS maior que o heap **não é** necessariamente problema: sem NMT não dá para atribuir
  a diferença.
- Antes/depois só vale com **mesma duração, mesma carga e horários comparáveis**.

## Limitações técnicas

- A **API pública do Paper/JVM não expõe** consumo por classe, objeto ou plugin. Sem
  ferramenta externa não há como provar a origem de um consumo.
- **Pausas de GC são estimativas**: a resolução é o intervalo de coleta (padrão 5 s);
  com múltiplas coletas no intervalo só a média é confiável.
- **RSS** vem de `/proc/self/status` (Linux). Em outros sistemas aparece como `n/d` —
  o plugin nunca inventa o valor.
- **`-Xmx`, flags de GC e coletores** não mudam depois do start; o plugin apenas lê e
  recomenda textualmente.
- Chunks/entidades são contagens agregadas: a API não informa **qual plugin** retém um
  chunk ou gera uma entidade.
- Nenhum método do plugin libera memória por si só — ele **previne novas alocações**,
  **classifica pressão** e **orienta a investigação**.

### Ferramentas externas (para ir além)

| Ferramenta | Uso |
|---|---|
| `jcmd <pid> GC.heap_dump=/tmp/heap.hprof` | Heap dump → Eclipse MAT / VisualVM |
| `jcmd <pid> VM.native_memory summary` | Composição da memória nativa (exige `-XX:NativeMemoryTracking=summary`) |
| `jcmd <pid> Thread.print` | Árvore de threads e pools |
| async-profiler / spark | Origem de alocação e de CPU por classe/plugin |
| `/spark profiler`, `/spark chunks`, `/spark timings` | Diagnóstico no servidor |

## Estrutura

```
pom.xml
src/main/resources/          plugin.yml, config.yml (mínimo)
src/main/kotlin/dev/miguel/otimizador/
  Otimizador.kt              classe principal, ciclos e tarefas
  core/                      buffers circulares, tendências, GC, estados, formatos
  config/                    Settings, parser validado, ConfigService
  memory/                    snapshots, coleta, análise de crescimento, nativo
  world/                     amostragem e controle de chunks/entidades
  controller/                alertas com rate-limit e controlador adaptativo
  jvm/                       leitura e recomendações de JVM
  diag/                      motor de diagnóstico (achados + evidências)
  report/                    gerador e gravador de relatórios
  command/                   /otimizador
src/test/kotlin/             128 testes (JUnit 5)
```

## Testes

```bash
mvn -B test
```

Cobrem: buffers circulares e limites rígidos, ajuste de tendências, máquina de estados
(histerese/persistência), janelas de GC, cache com TTL, parser de configuração, `config.yml`
empacotado, motor de diagnóstico, controlador adaptativo, rate-limit de alertas, análise de
crescimento, `/proc` e rotação de relatórios.
