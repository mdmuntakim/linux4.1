/*
 * fbdesk - a minimal framebuffer desktop and benchmark for Linux 4.1.
 *
 * It runs as a static musl binary inside the guest, draws a small "desktop"
 * straight into /dev/fb0 (bochs DRM fbdev) and measures how many complete
 * frames per second the guest can sustain.  It also reports input events from
 * /dev/input/event* so an outside harness can measure end-to-end latency.
 *
 * GPL-2.0.  The 8x16 console font in font8x16.h comes from the Linux kernel
 * (lib/fonts/font_8x16.c, GPL-2.0).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <linux/input.h>
#include <stdarg.h>
#include <stdint.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "font8x16.h"

#define MAX_BLOCKS 24
#define MAX_INPUT 16

struct screen {
    int fd;
    uint8_t *mem;
    size_t size;
    unsigned xres, yres, stride, bpp;
};

struct block {
    int x, y, dx, dy, w, h;
    uint32_t color;
};

static struct screen scr;
static struct block blocks[MAX_BLOCKS];
static int input_fd[MAX_INPUT];
static int input_n;
static int serial_fd = -1;
static unsigned long long frames;
static uint32_t ticks;
static int cursor_x = 512, cursor_y = 384;

static void say(const char *fmt, ...)
{
    char buf[256];
    va_list ap;
    int n;

    va_start(ap, fmt);
    n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0)
        return;
    if (serial_fd >= 0) {
        ssize_t ignored = write(serial_fd, buf, (size_t)n);
        (void)ignored;
    }
    fputs(buf, stdout);
    fflush(stdout);
}

static unsigned long long now_us(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000ull +
           (unsigned long long)ts.tv_nsec / 1000ull;
}

static uint32_t rgb(unsigned r, unsigned g, unsigned b)
{
    return (uint32_t)((r << 16) | (g << 8) | b);
}

static void put_pixel(int x, int y, uint32_t color)
{
    if (x < 0 || y < 0 || (unsigned)x >= scr.xres || (unsigned)y >= scr.yres)
        return;
    if (scr.bpp == 32) {
        *(uint32_t *)(scr.mem + (size_t)y * scr.stride + (size_t)x * 4) = color;
        return;
    }
    /* RGB 5:6:5 for the 16bpp case. */
    uint16_t v = (uint16_t)(((color >> 8) & 0xf800) |
                            ((color >> 5) & 0x07e0) |
                            ((color >> 3) & 0x001f));
    *(uint16_t *)(scr.mem + (size_t)y * scr.stride + (size_t)x * 2) = v;
}

static void fill_rect(int x, int y, int w, int h, uint32_t color)
{
    int i, j;

    for (j = 0; j < h; j++)
        for (i = 0; i < w; i++)
            put_pixel(x + i, y + j, color);
}

static void frame_rect(int x, int y, int w, int h, uint32_t color)
{
    fill_rect(x, y, w, 1, color);
    fill_rect(x, y + h - 1, w, 1, color);
    fill_rect(x, y, 1, h, color);
    fill_rect(x + w - 1, y, 1, h, color);
}

static void draw_char(int x, int y, int ch, int scale, uint32_t fg)
{
    const unsigned char *g = font8x16 + (unsigned)(ch & 0xff) * 16;
    int cx, cy, sx, sy;

    for (cy = 0; cy < 16; cy++) {
        unsigned char row = g[cy];
        for (cx = 0; cx < 8; cx++) {
            if (!(row & (0x80 >> cx)))
                continue;
            for (sy = 0; sy < scale; sy++)
                for (sx = 0; sx < scale; sx++)
                    put_pixel(x + cx * scale + sx, y + cy * scale + sy, fg);
        }
    }
}

static void draw_text_at(int x, int y, const char *s, int scale, uint32_t fg)
{
    for (; *s; s++, x += 8 * scale)
        draw_char(x, y, *s, scale, fg);
}

static void draw_gradient(void)
{
    unsigned y, x;
    uint32_t row;

    for (y = 0; y < scr.yres; y++) {
        unsigned r = 16 + (y * 40) / (scr.yres ? scr.yres : 1);
        unsigned g = 18 + (y * 12) / (scr.yres ? scr.yres : 1);
        unsigned b = 48 + (y * 70) / (scr.yres ? scr.yres : 1);
        row = rgb(r, g, b);
        if (scr.bpp == 32) {
            uint32_t *line = (uint32_t *)(scr.mem + (size_t)y * scr.stride);
            for (x = 0; x < scr.xres; x++)
                line[x] = row;
        } else {
            for (x = 0; x < scr.xres; x++)
                put_pixel((int)x, (int)y, row);
        }
    }
}

static void draw_cursor(void)
{
    static const char *arrow[] = {
        "X           ",
        "XX          ",
        "X.X         ",
        "X..X        ",
        "X...X       ",
        "X....X      ",
        "X.....X     ",
        "X......X    ",
        "X.......X   ",
        "X........X  ",
        "X.....XXXXX ",
        "X..X..X     ",
        "X.X X..X    ",
        "XX  X..X    ",
        "X    X..X   ",
        "     X..X   ",
        "      X..X  ",
        "      X..X  ",
        "       XX   ",
    };
    unsigned r, c;

    for (r = 0; r < sizeof(arrow) / sizeof(arrow[0]); r++) {
        for (c = 0; arrow[r][c]; c++) {
            if (arrow[r][c] == 'X')
                put_pixel(cursor_x + (int)c, cursor_y + (int)r, rgb(255, 255, 255));
            else if (arrow[r][c] == '.')
                put_pixel(cursor_x + (int)c, cursor_y + (int)r, rgb(20, 20, 20));
        }
    }
}

static void move_blocks(int w, int h)
{
    int i;

    for (i = 0; i < MAX_BLOCKS; i++) {
        struct block *b = &blocks[i];
        b->x += b->dx;
        b->y += b->dy;
        if (b->x < 0 || b->x + b->w > w) {
            b->dx = -b->dx;
            b->x += b->dx;
        }
        if (b->y < 32 || b->y + b->h > h - 32) {
            b->dy = -b->dy;
            b->y += b->dy;
        }
    }

    /* The cursor follows a circle, so input and rendering stay independent. */
    {
        static int angle;
        double rad;

        angle = (angle + 3) % 360;
        rad = (double)angle * M_PI / 180.0;
        cursor_x = (int)(scr.xres / 2) +
                   (int)((double)(scr.xres / 2 - 20) * cos(rad));
        cursor_y = (int)(scr.yres / 2) +
                   (int)((double)(scr.yres / 2 - 20) * sin(rad));
    }
}

static void draw_frame(void)
{
    int i;
    char label[128];

    draw_gradient();
    move_blocks((int)scr.xres, (int)scr.yres);

    /* Top bar. */
    fill_rect(0, 0, (int)scr.xres, 30, rgb(10, 12, 26));
    draw_text_at(8, 7, "Linux 4.1.52", 2, rgb(235, 240, 255));

    /* Desktop icons. */
    for (i = 0; i < 4; i++) {
        fill_rect(24, 64 + i * 96, 64, 64, rgb(60, 90, 160));
        frame_rect(24, 64 + i * 96, 64, 64, rgb(160, 190, 255));
    }

    /* Window. */
    {
        int wx = (int)(scr.xres / 8), wy = (int)(scr.yres / 6);
        int ww = (int)(scr.xres * 3 / 4), wh = (int)(scr.yres * 3 / 5);
        fill_rect(wx, wy, ww, wh, rgb(232, 235, 245));
        fill_rect(wx, wy, ww, 26, rgb(40, 60, 120));
        draw_text_at(wx + 8, wy + 5, "Terminal - linux 4.1.52", 1,
                     rgb(255, 255, 255));
        fill_rect(wx + 10, wy + 34, ww - 20, wh - 44, rgb(16, 18, 24));
        draw_text_at(wx + 20, wy + 44, "uname -r", 1, rgb(120, 230, 140));
        draw_text_at(wx + 20, wy + 62, "4.1.52", 1, rgb(220, 220, 220));
        draw_text_at(wx + 20, wy + 86, "qemu-system-aarch64 -M virt", 1,
                     rgb(120, 230, 140));
        draw_text_at(wx + 20, wy + 104, "tcg, thread=multi", 1,
                     rgb(220, 220, 220));
    }

    for (i = 0; i < MAX_BLOCKS; i++) {
        struct block *b = &blocks[i];
        fill_rect(b->x, b->y, b->w, b->h, b->color);
        frame_rect(b->x, b->y, b->w, b->h, rgb(255, 255, 255));
    }

    draw_cursor();

    /* Bottom status bar. */
    fill_rect(0, (int)scr.yres - 30, (int)scr.xres, 30, rgb(10, 12, 26));
    snprintf(label, sizeof(label), "frame %llu   fb %ux%u %ubpp   %s",
             frames, scr.xres, scr.yres, scr.bpp,
             getenv("FB_MODE") ? getenv("FB_MODE") : "desktop");
    draw_text_at(8, (int)scr.yres - 23, label, 1, rgb(200, 210, 230));
}

static void init_input(void)
{
    int i;

    for (i = 0; i < MAX_INPUT; i++) {
        char path[32];
        unsigned long bits = 0;
        int fd;

        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0)
            continue;
        if (ioctl(fd, EVIOCGBIT(0, sizeof(bits)), &bits) < 0) {
            close(fd);
            continue;
        }
        if (!(bits & (1ul << EV_KEY)) && !(bits & (1ul << EV_REL))) {
            close(fd);
            continue;
        }
        input_fd[input_n++] = fd;
    }
}

static void poll_input(void)
{
    int i;

    for (i = 0; i < input_n; i++) {
        struct input_event ev[16];
        ssize_t n = read(input_fd[i], ev, sizeof(ev));
        ssize_t k;

        if (n <= 0)
            continue;
        for (k = 0; k < n / (ssize_t)sizeof(ev[0]); k++) {
            if (ev[k].type == EV_KEY && ev[k].value == 1) {
                say("INPUT_ACK %llu code=%u\n", now_us(), ev[k].code);
            } else if (ev[k].type == EV_REL) {
                say("INPUT_ACK %llu rel=%u\n", now_us(), ev[k].code);
            }
        }
    }
}

static int open_screen(void)
{
    struct fb_fix_screeninfo fix;
    struct fb_var_screeninfo var;

    scr.fd = open("/dev/fb0", O_RDWR);
    if (scr.fd < 0) {
        fprintf(stderr, "fbdesk: open /dev/fb0: %s\n", strerror(errno));
        return -1;
    }
    if (ioctl(scr.fd, FBIOGET_FSCREENINFO, &fix) < 0 ||
        ioctl(scr.fd, FBIOGET_VSCREENINFO, &var) < 0) {
        fprintf(stderr, "fbdesk: fb ioctl: %s\n", strerror(errno));
        return -1;
    }
    scr.xres = var.xres;
    scr.yres = var.yres;
    scr.bpp = var.bits_per_pixel;
    scr.stride = fix.line_length;
    scr.size = (size_t)fix.line_length * var.yres;
    scr.mem = mmap(NULL, scr.size, PROT_READ | PROT_WRITE, MAP_SHARED,
                   scr.fd, 0);
    if (scr.mem == MAP_FAILED) {
        fprintf(stderr, "fbdesk: mmap fb: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static void init_blocks(void)
{
    int i;
    static const uint32_t palette[] = {
        0xff7043, 0x42a5f5, 0x66bb6a, 0xffee58, 0xab47bc, 0x26c6da,
    };

    for (i = 0; i < MAX_BLOCKS; i++) {
        blocks[i].w = 48;
        blocks[i].h = 32;
        blocks[i].x = 40 + (i * 37) % (int)(scr.xres - 100);
        blocks[i].y = 80 + (i * 53) % (int)(scr.yres - 160);
        blocks[i].dx = (i % 2) ? 3 : -3;
        blocks[i].dy = (i % 3) ? 2 : -2;
        blocks[i].color = palette[i % (int)(sizeof(palette) / sizeof(palette[0]))];
    }
}

int main(int argc, char **argv)
{
    int seconds = 0;
    int i;
    unsigned long long start, last, deadline = 0;
    unsigned long long boot_mark = now_us();

    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--seconds") && i + 1 < argc)
            seconds = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--mode") && i + 1 < argc)
            setenv("FB_MODE", argv[++i], 1);
    }

    serial_fd = open("/dev/ttyS0", O_WRONLY | O_NOCTTY);
    if (serial_fd < 0)
        serial_fd = open("/dev/ttyAMA0", O_WRONLY | O_NOCTTY);

    if (open_screen() < 0)
        return 1;

    init_blocks();
    init_input();

    say("FB_DESK_READY %llu fb=%ux%u bpp=%u input=%d\n",
        boot_mark, scr.xres, scr.yres, scr.bpp, input_n);
    if (serial_fd >= 0)
        say("LINUX4.1_UNAME_BEGIN\n");

    start = now_us();
    if (seconds > 0)
        deadline = start + (unsigned long long)seconds * 1000000ull;

    last = start;
    for (;;) {
        unsigned long long t;

        draw_frame();
        frames++;
        t = now_us();
        if (deadline && t >= deadline)
            break;
        if (!deadline && t - last >= 5000000ull) {
            double fps = (double)frames * 1000000.0 / (double)(t - start);
            say("STATUS fps=%.2f frames=%llu\n", fps, frames);
            last = t;
        }
        poll_input();
        if (!deadline && (frames % 16) == 0)
            usleep(1000);
    }

    {
        unsigned long long total = now_us() - start;
        double fps = (double)frames * 1000000.0 / (double)total;
        say("RESULT fps=%.2f frames=%llu total_us=%llu fb=%ux%u bpp=%u\n",
            fps, frames, total, scr.xres, scr.yres, scr.bpp);
        say("LINUX4.1_UNIXTIME %llu\n", (unsigned long long)time(NULL));
    }
    return 0;
}
