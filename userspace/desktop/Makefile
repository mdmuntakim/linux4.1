CC ?= aarch64-linux-gnu-gcc-13
CFLAGS ?= -Os -static -s -ffunction-sections -fdata-sections -Wall -Wextra
LDFLAGS ?= -static -Wl,--gc-sections -s
LDLIBS ?= -lm

fbdesk: fbdesk.c font8x16.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ fbdesk.c $(LDLIBS)

clean:
	rm -f fbdesk

.PHONY: clean
