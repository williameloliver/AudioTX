/* Receptor de audio para Windows XP (Win32, C puro)
   Compilar: gcc recv.c -o recv.exe -mwindows -static -lws2_32 -lwinmm
   En Dev-C++: usar compilador de 32 bits y en Linker: -lws2_32 -lwinmm -mwindows -static */
#define _WIN32_WINNT 0x0501
#include <winsock2.h>
#include <windows.h>
#include <mmsystem.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef TCP_NODELAY
#define TCP_NODELAY 1
#endif

#define PORT 5000
#define NBUF 32

static HWND hMain, hStatus;

static void Status(const char *s) { SetWindowText(hStatus, s); }

static int recvAll(SOCKET s, char *b, int n) {
    int got = 0, r;
    while (got < n) {
        r = recv(s, b + got, n - got, 0);
        if (r <= 0) return -1;
        got += r;
    }
    return 0;
}

static void Play(SOCKET c, unsigned char *h) {
    unsigned rate = h[4] | (h[5] << 8) | (h[6] << 16) | ((unsigned)h[7] << 24);
    int bits = h[8], ch = h[9];
    int ms = h[10] | (h[11] << 8), pre = h[12] | (h[13] << 8);
    int align = ch * bits / 8;
    int frames = rate * ms / 1000;
    int chunk = frames * align;
    int npre, i = 0, written = 0, started = 0, k;
    WAVEFORMATEX wf;
    HWAVEOUT hwo;
    WAVEHDR *hdr;
    char *data, msg[128];

    if (chunk <= 0 || (bits != 8 && bits != 16) || ch < 1 || ch > 2) return;
    npre = pre / (ms ? ms : 1);
    if (npre < 1) npre = 1;
    if (npre > NBUF - 2) npre = NBUF - 2;

    memset(&wf, 0, sizeof(wf));
    wf.wFormatTag = WAVE_FORMAT_PCM;
    wf.nChannels = ch;
    wf.nSamplesPerSec = rate;
    wf.wBitsPerSample = bits;
    wf.nBlockAlign = align;
    wf.nAvgBytesPerSec = rate * align;
    if (waveOutOpen(&hwo, WAVE_MAPPER, &wf, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR) {
        Status("Error abriendo audio");
        return;
    }
    waveOutPause(hwo);
    hdr = (WAVEHDR *)calloc(NBUF, sizeof(WAVEHDR));
    data = (char *)malloc(NBUF * chunk);
    sprintf(msg, "Reproduciendo %u Hz, %d bits, %d canal(es), %d ms, prebuffer %d ms",
            rate, bits, ch, ms, npre * ms);
    Status(msg);

    for (;;) {
        WAVEHDR *w = &hdr[i];
        while ((w->dwFlags & WHDR_PREPARED) && !(w->dwFlags & WHDR_DONE)) Sleep(2);
        if (w->dwFlags & WHDR_PREPARED) waveOutUnprepareHeader(hwo, w, sizeof(WAVEHDR));
        w->lpData = data + i * chunk;
        w->dwBufferLength = chunk;
        w->dwFlags = 0;
        if (recvAll(c, w->lpData, chunk) < 0) break;
        waveOutPrepareHeader(hwo, w, sizeof(WAVEHDR));
        waveOutWrite(hwo, w, sizeof(WAVEHDR));
        written++;
        if (!started && written >= npre) { waveOutRestart(hwo); started = 1; }
        i = (i + 1) % NBUF;
    }
    waveOutReset(hwo);
    for (k = 0; k < NBUF; k++)
        if (hdr[k].dwFlags & WHDR_PREPARED) waveOutUnprepareHeader(hwo, &hdr[k], sizeof(WAVEHDR));
    waveOutClose(hwo);
    free(hdr);
    free(data);
}

static DWORD WINAPI NetThread(LPVOID p) {
    WSADATA wd;
    SOCKET ls, c;
    struct sockaddr_in a;
    char host[128], msg[200];
    struct hostent *he;
    int nd = 1;
    unsigned char h[14];

    WSAStartup(MAKEWORD(2, 2), &wd);
    ls = socket(AF_INET, SOCK_STREAM, 0);
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = INADDR_ANY;
    a.sin_port = htons(PORT);
    bind(ls, (struct sockaddr *)&a, sizeof(a));
    listen(ls, 1);

    gethostname(host, sizeof(host));
    he = gethostbyname(host);
    if (he && he->h_addr_list[0]) {
        struct in_addr ia;
        memcpy(&ia, he->h_addr_list[0], 4);
        sprintf(msg, "Esperando en %s:%d", inet_ntoa(ia), PORT);
    } else sprintf(msg, "Esperando en puerto %d", PORT);

    for (;;) {
        Status(msg);
        c = accept(ls, 0, 0);
        if (c == INVALID_SOCKET) break;
        setsockopt(c, IPPROTO_TCP, TCP_NODELAY, (char *)&nd, sizeof(nd));
        if (recvAll(c, (char *)h, 14) == 0 && memcmp(h, "AUD1", 4) == 0) Play(c, h);
        closesocket(c);
    }
    return 0;
}

static LRESULT CALLBACK WndProc(HWND w, UINT m, WPARAM wp, LPARAM lp) {
    if (m == WM_DESTROY) { PostQuitMessage(0); ExitProcess(0); return 0; }
    return DefWindowProc(w, m, wp, lp);
}

int WINAPI WinMain(HINSTANCE hi, HINSTANCE hp, LPSTR cl, int sh) {
    WNDCLASS wc;
    MSG msg;
    memset(&wc, 0, sizeof(wc));
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hi;
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.hCursor = LoadCursor(0, IDC_ARROW);
    wc.lpszClassName = "AudRx";
    RegisterClass(&wc);
    hMain = CreateWindow("AudRx", "Receptor de audio",
                         WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX | WS_VISIBLE,
                         100, 100, 480, 90, 0, 0, hi, 0);
    hStatus = CreateWindow("STATIC", "Iniciando...", WS_CHILD | WS_VISIBLE, 10, 10, 450, 20, hMain, 0, hi, 0);
    CreateThread(0, 0, NetThread, 0, 0, 0);
    while (GetMessage(&msg, 0, 0, 0)) { TranslateMessage(&msg); DispatchMessage(&msg); }
    return 0;
}
