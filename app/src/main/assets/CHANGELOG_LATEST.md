### v0.1.3 - Correção do Daemon Root & Pipeline de Áudio
- Correção na inicialização do daemon com `ANDROID_DATA` e cache Dalvik para app_process.
- Implementação de streaming direto por canal anônimo de processo e fallback de LocalSocket.
- Inicialização defensiva do Looper e tentativas de reconexão do Visualizer na Sessão 0.
- Renderização visual aprimorada de guias de trilhas de espectro em estado ocioso.
