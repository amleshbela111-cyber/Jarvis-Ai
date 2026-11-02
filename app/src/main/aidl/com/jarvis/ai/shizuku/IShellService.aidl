package com.jarvis.ai.shizuku;

interface IShellService {
    String run(String command) = 1;
    void destroy() = 16777114;
}
