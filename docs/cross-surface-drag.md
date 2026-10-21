# Design: arraste entre superfícies (dock ⇄ tela inicial)

Data: 2026-06-24 · Status: aprovado, em implementação

## Objetivo
Arrastar um ícone de app **da dock para fora, até a tela inicial**, e um **ícone da tela inicial
para dentro da dock** — nas duas direções, do jeito que launchers de verdade (Launcher3) fazem.

## Abordagem (modelo do Launcher3, adaptado para o Compose)
O Launcher3 usa uma **camada de arraste** em tela cheia + um **controlador de arraste** central com
alvos de soltura registrados; a posição do dedo decide o destino da soltura. Restrição do Compose:
um gesto de ponteiro pertence ao nó que capturou o DOWN e não pode ser transferido no meio do
gesto. Portanto:

- **A captura e a detecção do gesto ficam por superfície** (ícone da Workspace, ícone da Dock) — a
  lógica delicada de pressionar-e-segurar / toque / deslize em cada uma é preservada (veja a
  memória `home-drag-gesture-architecture`).
- **O ícone flutuante e o roteamento da soltura são elevados para `HomeScreen`** via um
  `HomeDragController` compartilhado — este é o equivalente à camada/controlador de arraste.

## Componentes
- **`HomeDragController`** (lembrado em `HomeScreen`): `draggedApp`, `source` (Home/Dock),
  `rootPosition` (dedo, coordenadas raiz), métricas de grade (cellW/cellH/columns/rows/currentPage,
  origem da workspace), e os limites `workspaceBounds` / `dockBounds` (coordenadas raiz).
- **`HomeScreen`**: possui o controlador, captura os limites de Workspace/Dock via
  `onGloballyPositioned` (`boundsInRoot`), e desenha o **único ícone flutuante** acima de ambas as
  superfícies durante o arraste.
- **`Workspace`**: mantém a captura/arraste internos; atualiza o controlador (posição raiz +
  métricas de grade); sua própria sobreposição flutuante é removida (quem desenha é a
  `HomeScreen`). Ao soltar: sobre a dock → move para a dock; senão, move/troca normal dentro da
  tela inicial.
- **`Dock`**: mantém o pressionar-e-segurar interno; deslocamento horizontal = reordenar
  (inalterado); atualiza o controlador para deslocamento vertical/para fora. Ao soltar: sobre a
  workspace → move para a tela inicial na célula sob o dedo; senão, reordena normalmente.

## Semântica da soltura (semântica de mover, como launchers de verdade)
- **Dock → tela inicial**: posiciona na célula sob o dedo, troca se ocupada (`placeAt`); **remove
  da dock**.
- **Tela inicial → dock**: insere no slot sob o dedo (`addToDockAt`); **remove da tela inicial**.
  Se a dock já estiver cheia (`dockColumns` visíveis), **bloqueia** a soltura (vibração tátil, sem
  alteração).
- **Mesma superfície**: inalterado (mover/trocar na tela inicial; reordenar na dock).
- **Solto em nenhuma das duas** (vão vazio): cancela — o ícone volta à origem.

## Mudanças no repositório
- `HomeLayoutRepository.placeAt(app, page, cellX, cellY): Boolean` — insere ou troca em uma célula
  específica, em uma única transação (reaproveita a mecânica de troca de `moveItem` via o slot
  temporário fora da grade).
- `SettingsRepository.addToDockAt(key, index)` — inserção posicional nos favoritos, sem
  duplicatas.

## Testes
- Gestos não são testáveis de forma unitária de maneira significativa → verificação manual em
  emulador + Pixel (build → instalar → capturas de tela) para o fluxo entre superfícies.
- `placeAt` recebe um teste instrumentado do Room (como `moveItem`); `addToDockAt` recebe um teste
  JVM (FakeDataStore).

## Risco
O passo mais arriscado é remover a sobreposição flutuante interna da Workspace e conectá-la ao
controlador compartilhado sem regredir o arraste já ajustado. Implementar em fases, verificando no
dispositivo depois de cada uma.
