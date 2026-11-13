#include "launcher_window.h"

#include <qt2wasm/gui.h>

int qt2wasm_gui_main(int, char **) {
    auto *w = new LauncherWindow;
    w->show();
    return 0;
}
