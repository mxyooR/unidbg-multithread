#include "com_github_unidbg_arm_backend_unicorn_Unicorn.h"
#include "khash.h"
#include <stdatomic.h>
#include <unicorn/unicorn.h>

#define SEARCH_BPS_COUNT 8

KHASH_MAP_INIT_INT64(64, char)

typedef enum {
  STOP_NONE = 0,
  STOP_NORMAL = 1,
  STOP_TIMESLICE = 2,
  STOP_EMU_STOP = 3,
  STOP_FAULT = 4,
  STOP_TIMEOUT = 5
} stop_reason;

typedef struct unicorn {
  khash_t(64) *bps_map;
  uint64_t bps[SEARCH_BPS_COUNT];
  uc_engine *uc;
  jboolean is64Bit;
  jint singleStep;
  jboolean fastDebug;
  uc_hook count_hook;
  jboolean count_hook_enabled;
  jboolean count_hook_triggered;
  uint64_t emu_count;
  uint64_t emu_counter;
  uc_hook timeslice_hook;
  jboolean timeslice_enabled;
  uint64_t timeslice_budget;
  uint64_t timeslice_counter;
  uint64_t last_stop_pc;
  atomic_int last_stop_reason;
  atomic_int cross_thread_stop_request;
} *t_unicorn;

struct new_hook {
    uc_hook hh;
    jobject hook;
    t_unicorn unicorn;
};

void armeb_uc_init() {
  fprintf(stderr, "Unsupported armeb\n");
  abort();
}
void arm64eb_uc_init() {
  fprintf(stderr, "Unsupported arm64eb\n");
  abort();
}
void arm64eb_context_reg_read() {
  fprintf(stderr, "Unsupported arm64eb\n");
  abort();
}
void arm64eb_context_reg_write() {
  fprintf(stderr, "Unsupported arm64eb\n");
  abort();
}
void ARM64_REGS_STORAGE_SIZE_aarch64eb() {
  fprintf(stderr, "Unsupported aarch64eb\n");
  abort();
}
