#include <assert.h>
#include <stdio.h>
#include "../../main/cpp/watchdog_logic.h"

int main(void) {
    assert(parse_rear_state("Displays:\n Display id 0: DisplayInfo{, state OFF}\n Display id 1: DisplayInfo{, state ON}\n") == 0);
    assert(parse_rear_state(" Display id 1: DisplayInfo{, state OFF}\n") == 1);
    assert(parse_rear_state("Display id 1: DisplayInfo{, state DOZE_SUSPEND, flags 0}") == 1);
    assert(parse_rear_state("Display id 1: DisplayInfo{, state ON_SUSPEND}") == 1);
    assert(parse_rear_state("Display id 10: DisplayInfo{, state OFF}\n") == -1);
    assert(parse_rear_state("Display id 1: no state\nDisplay id 2: DisplayInfo{, state OFF}\n") == -1);
    assert(parse_rear_state("Display id 1: DisplayInfo{, state UNKNOWN}\n") == -1);
    assert(parse_rear_state("Display id 1: DisplayInfo{, state ON_BROKEN}\n") == -1);
    int value;
    assert(parse_target("-1", 4095, &value) == 0 && value == -1);
    assert(parse_target("10", 4095, &value) == 0 && value == 10);
    assert(parse_target("4095", 4095, &value) == 0 && value == 4095);
    assert(parse_target("0", 4095, &value) == -1);
    assert(parse_target("4096", 4095, &value) == -1);
    assert(parse_target("10junk", 4095, &value) == -1);
    assert(parse_target("9999999999999999999999999", 4095, &value) == -1);
    puts("15 native parser/bounds checks passed");
    return 0;
}
