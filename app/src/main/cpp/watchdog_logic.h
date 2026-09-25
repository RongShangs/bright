#ifndef BRIGHT_WATCHDOG_LOGIC_H
#define BRIGHT_WATCHDOG_LOGIC_H
#include <string.h>
#include <stdlib.h>
#include <errno.h>

/* -1 unknown/error; 0 awake; 1 requires wakeup. Match only display 1's own line. */
static int parse_rear_state(const char *text) {
    const char *line = text;
    while (*line) {
        const char *end = strchr(line, '\n');
        if (!end) end = line + strlen(line);
        while (line < end && (*line == ' ' || *line == '\t')) ++line;
        if ((size_t)(end - line) >= 13 && !strncmp(line, "Display id 1:", 13)) {
            const char *state = strstr(line, ", state ");
            if (!state || state >= end) return -1;
            state += 8;
            const char *names[] = {"ON", "VR", "OFF", "DOZE", "DOZE_SUSPEND", "ON_SUSPEND"};
            for (int i = 0; i < 6; ++i) {
                size_t n = strlen(names[i]);
                if (state + n <= end && !strncmp(state, names[i], n) &&
                    (state + n == end || state[n] == ',' || state[n] == '}' || state[n] == ' '))
                    return i < 2 ? 0 : 1;
            }
            return -1;
        }
        line = *end ? end + 1 : end;
    }
    return -1;
}

static int parse_target(const char *text, int maximum, int *value) {
    char *end;
    errno = 0;
    long parsed = strtol(text, &end, 10);
    if (errno || end == text || *end || (parsed != -1 && (parsed < 10 || parsed > maximum))) return -1;
    *value = (int) parsed;
    return 0;
}
#endif
