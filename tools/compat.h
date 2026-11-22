/* Host tests were written against the pre-plug-in API; map it onto the mod API of
 * games/contraforce.c. Included by build flags (-include tools/compat.h). */
#ifndef COMPAT_H_
#define COMPAT_H_
#include "gbcplayer.h"
static inline int cf_mod(const char* k) { return gbc_mod_find(k); }
#define gbc_get_lives(e) gbc_mod_get(cf_mod("lives"))
#define gbc_set_lives(e, n) gbc_mod_set(cf_mod("lives"), (n))
#define gbc_set_invincible(e, on) gbc_mod_set(cf_mod("invincible"), (on))
#define gbc_set_best_weapon(e, on) gbc_mod_set(cf_mod("best_weapon"), (on))
static inline void cf_reset_stage(GbcEmu* e, int s) {
  if (s) { gbc_mod_set(cf_mod("stage"), s); gbc_mod_set(cf_mod("start_stage"), 1); }
  else (gbc_reset)(e);
}
#define gbc_reset(e, s) cf_reset_stage((e), (s))
#endif
