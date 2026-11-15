### v0.1.8 - Otimização de Performance, Eliminação de Lag e Redução de Bateria
- **Eliminação de Lag em Tempo Real:** Remoção de latência acumulada no pipe via `BufferedInputStream` com descarte inteligente de pacotes defasados, garantindo sincronia perfeita com a música.
- **Balística Física Instantânea:** Ataque ultrarrápido (subida das barras em 1-2 frames) e decaimento dinâmico suave perfeitamente calibrado para o ritmo musical.
- **Eficiência do Daemon Root:** Transmissão instantânea do espectro RMS de Parseval sem filtro EMA artificial atrasado e eliminação de IPCs síncronos redundantes no AudioFlinger.
- **Bateria 0% em Segundo Plano:** O daemon bloqueia nativamente no kernel Linux (`TASK_INTERRUPTIBLE`) sem loops de espera ativa, e o processo root é encerrado após 15 segundos sem uso da interface ou de tela desligada.
- **Fim dos Engasgos no App:** Isolamento das recomposições do Jetpack Compose na tela principal, eliminando acúmulo de objetos e pausas de Garbage Collection (GC) durante a reprodução.
