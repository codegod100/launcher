use android_activity::input::{InputEvent, KeyAction, Keycode, MotionAction};
use android_activity::{AndroidApp, AndroidAppWaker, InputStatus, MainEvent, PollEvent};
use base64::Engine as _;
use jni::objects::{GlobalRef, JByteArray, JObject, JString, JValue};
use jni::sys::jint;
use jni::{JNIEnv, JavaVM};
use ndk::hardware_buffer_format::HardwareBufferFormat;
use ndk::native_window::NativeWindow;
use sha2::{Digest, Sha256};
use std::collections::VecDeque;
use std::ffi::CString;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use wasmtime::component::{Component, HasSelf, Linker, ResourceTable};
use wasmtime::{Config, Engine, Store};
use wasmtime_wasi::{FsPerms, WasiCtx, WasiCtxBuilder, WasiCtxView, WasiView};

wasmtime::component::bindgen!({
    path: "../wasi/wit",
    world: "gui",
});

use latha::gui::types as wt;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;
const MIN_FRAME: Duration = Duration::from_millis(16);

static EVENTS: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static WAKER: Mutex<Option<AndroidAppWaker>> = Mutex::new(None);
static INSET_TOP: AtomicU32 = AtomicU32::new(0);
static INSET_BOTTOM: AtomicU32 = AtomicU32::new(0);

#[no_mangle]
pub extern "system" fn Java_dev_ultra_launcher_wasm_LauncherActivity_nativeOnEvent(
    mut env: JNIEnv,
    _class: JObject,
    event: JString,
) {
    if let Ok(s) = env.get_string(&event) {
        if let Ok(mut q) = EVENTS.lock() {
            q.push_back(s.to_string_lossy().into_owned());
        }
        if let Ok(w) = WAKER.lock() {
            if let Some(waker) = w.as_ref() {
                waker.wake();
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_ultra_launcher_wasm_LauncherActivity_nativeOnInsets(
    _env: JNIEnv,
    _class: JObject,
    top: jint,
    bottom: jint,
) {
    INSET_TOP.store(top.max(0) as u32, Ordering::Relaxed);
    INSET_BOTTOM.store(bottom.max(0) as u32, Ordering::Relaxed);
}

struct HostState {
    wasi: WasiCtx,
    table: ResourceTable,
    vm: JavaVM,
    activity: GlobalRef,
    frame_wanted: bool,
    tick_at: Option<Instant>,
    quit: Option<i32>,
    clipboard: String,
    primary: String,
    dark: bool,
}

impl WasiView for HostState {
    fn ctx(&mut self) -> WasiCtxView<'_> {
        WasiCtxView {
            ctx: &mut self.wasi,
            table: &mut self.table,
        }
    }
}

impl HostState {
    fn call_void(&self, method: &str, sig: &str, args: &[JValue]) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            if let Err(e) = env.call_method(&self.activity, method, sig, args) {
                log::warn!("JNI {method} failed: {e}");
            }
        }
    }

    fn call_void_str(&self, method: &str, arg: &str) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            if let Ok(js) = env.new_string(arg) {
                if let Err(e) = env.call_method(
                    &self.activity,
                    method,
                    "(Ljava/lang/String;)V",
                    &[JValue::Object(&js)],
                ) {
                    log::warn!("JNI {method}({arg}) failed: {e}");
                }
            }
        }
    }
}

impl latha::gui::host::Host for HostState {
    fn request_frame(&mut self) {
        self.frame_wanted = true;
    }
    fn request_tick(&mut self, delay_ms: u32) {
        self.tick_at = Some(Instant::now() + Duration::from_millis(delay_ms as u64));
    }
    fn set_title(&mut self, title: String) {
        log::info!("title: {title}");
    }
    fn set_cursor(&mut self, _shape: latha::gui::host::Cursor) {}
    fn set_min_size(&mut self, _min: latha::gui::host::Size) {}
    fn clipboard_text(&mut self) -> String {
        self.clipboard.clone()
    }
    fn set_clipboard_text(&mut self, text: String) {
        self.clipboard = text;
    }
    fn primary_text(&mut self) -> String {
        self.primary.clone()
    }
    fn set_primary_text(&mut self, text: String) {
        self.primary = text;
    }
    fn prefers_dark(&mut self) -> bool {
        self.dark
    }
    fn accent_color(&mut self) -> Option<u32> {
        None
    }
    fn bell(&mut self) {}
    fn set_text_input(&mut self, active: bool) {
        self.call_void("setTextInput", "(Z)V", &[JValue::Bool(active as u8)]);
    }
    fn open_url(&mut self, _url: String) -> bool {
        false
    }
    fn quit(&mut self, code: i32) {
        self.quit = Some(code);
    }
}

impl ultra::launcher::android::Host for HostState {
    fn apps(&mut self) -> String {
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return "{\"apps\":[]}".to_owned();
        };
        let Ok(res) = env.call_method(&self.activity, "apps", "()Ljava/lang/String;", &[]) else {
            return "{\"apps\":[]}".to_owned();
        };
        let Ok(obj) = res.l() else {
            return "{\"apps\":[]}".to_owned();
        };
        let js: JString = obj.into();
        env.get_string(&js)
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "{\"apps\":[]}".to_owned())
    }

    fn icon(&mut self, key: String, size: u32) -> Option<String> {
        let mut env = self.vm.attach_current_thread().ok()?;
        let js = env.new_string(&key).ok()?;
        let res = env
            .call_method(
                &self.activity,
                "icon",
                "(Ljava/lang/String;I)[B",
                &[JValue::Object(&js), JValue::Int(size as jint)],
            )
            .ok()?;
        let obj = res.l().ok()?;
        if obj.is_null() {
            return None;
        }
        let arr: JByteArray = obj.into();
        let bytes = env.convert_byte_array(arr).ok()?;
        Some(B64.encode(&bytes))
    }

    fn launch(&mut self, component: String) {
        self.call_void_str("launch", &component);
    }
    fn app_info(&mut self, component: String) {
        self.call_void_str("appInfo", &component);
    }
    fn uninstall(&mut self, component: String) {
        self.call_void_str("uninstall", &component);
    }
    fn open_settings(&mut self) {
        self.call_void("openSettings", "()V", &[]);
    }
    fn pick_wallpaper(&mut self) {
        self.call_void("pickWallpaper", "()V", &[]);
    }
    fn open_recents(&mut self) {
        self.call_void("openRecents", "()V", &[]);
    }
    fn haptic(&mut self) {
        self.call_void("haptic", "()V", &[]);
    }
    fn insets(&mut self) -> (u32, u32) {
        (
            INSET_TOP.load(Ordering::Relaxed),
            INSET_BOTTOM.load(Ordering::Relaxed),
        )
    }
    fn poll_events(&mut self) -> Vec<String> {
        let mut out = Vec::new();
        if let Ok(mut q) = EVENTS.lock() {
            while let Some(ev) = q.pop_front() {
                out.push(ev);
            }
        }
        out
    }
    fn widget_providers(&mut self) -> String {
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return "[]".to_owned();
        };
        let Ok(res) = env.call_method(&self.activity, "widgetProviders", "()Ljava/lang/String;", &[]) else {
            return "[]".to_owned();
        };
        let Ok(obj) = res.l() else {
            return "[]".to_owned();
        };
        let js: JString = obj.into();
        env.get_string(&js)
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "[]".to_owned())
    }
    fn add_widget(&mut self, provider: String) {
        self.call_void_str("addWidget", &provider);
    }
    fn remove_widget(&mut self, id: i32) {
        self.call_void("removeWidget", "(I)V", &[JValue::Int(id as jint)]);
    }
    fn place_widgets(&mut self, json: String) {
        self.call_void_str("placeWidgets", &json);
    }
    fn set_widgets_visible(&mut self, visible: bool) {
        self.call_void(
            "setWidgetsVisible",
            "(Z)V",
            &[JValue::Bool(if visible { 1 } else { 0 })],
        );
    }
}

struct FrameBuf {
    width: u32,
    height: u32,
    pixels: Vec<u8>, // RGBA8888 premultiplied
}

impl FrameBuf {
    fn new() -> Self {
        Self {
            width: 0,
            height: 0,
            pixels: Vec::new(),
        }
    }

    fn resize(&mut self, w: u32, h: u32) {
        if self.width != w || self.height != h {
            self.width = w;
            self.height = h;
            self.pixels.resize((w as usize) * (h as usize) * 4, 0);
        }
    }

    fn apply_damage(&mut self, store: &mut Store<HostState>, app: &Gui, dmg: &wt::Damage) {
        let fw = self.width as i32;
        let fh = self.height as i32;
        for m in &dmg.moves {
            let x0 = m.area.x.max(0);
            let y0 = m.area.y.max(0);
            let x1 = (m.area.x + m.area.width as i32).min(fw);
            let y1 = (m.area.y + m.area.height as i32).min(fh);
            if x1 <= x0 || y1 <= y0 {
                continue;
            }
            let w = (x1 - x0) as usize;
            let h = (y1 - y0) as usize;
            let stride = (self.width as usize) * 4;
            let mut tmp = vec![0u8; w * h * 4];
            for row in 0..h {
                let src_off = ((y0 as usize + row) * stride) + (x0 as usize) * 4;
                tmp[row * w * 4..(row + 1) * w * 4]
                    .copy_from_slice(&self.pixels[src_off..src_off + w * 4]);
            }
            for row in 0..h {
                let dy = y0 + row as i32 + m.dy;
                if dy < 0 || dy >= fh {
                    continue;
                }
                for col in 0..w {
                    let dx = x0 + col as i32 + m.dx;
                    if dx < 0 || dx >= fw {
                        continue;
                    }
                    let dst_off = ((dy as usize) * stride) + (dx as usize) * 4;
                    let src_off = (row * w + col) * 4;
                    self.pixels[dst_off..dst_off + 4]
                        .copy_from_slice(&tmp[src_off..src_off + 4]);
                }
            }
        }

        for r in &dmg.rects {
            if r.width == 0 || r.height == 0 {
                continue;
            }
            let Ok(b64) = app.latha_gui_app().call_frame_pixels(&mut *store, *r) else {
                continue;
            };
            let Ok(bgra) = B64.decode(b64.as_bytes()) else {
                continue;
            };
            let rw = r.width as usize;
            let rh = r.height as usize;
            if bgra.len() < rw * rh * 4 {
                continue;
            }
            let stride = (self.width as usize) * 4;
            for row in 0..rh {
                let py = r.y + row as i32;
                if py < 0 || py >= fh {
                    continue;
                }
                for col in 0..rw {
                    let px = r.x + col as i32;
                    if px < 0 || px >= fw {
                        continue;
                    }
                    let src = (row * rw + col) * 4;
                    let dst = (py as usize) * stride + (px as usize) * 4;
                    // BGRA -> RGBA (premultiplied)
                    self.pixels[dst] = bgra[src + 2];
                    self.pixels[dst + 1] = bgra[src + 1];
                    self.pixels[dst + 2] = bgra[src];
                    self.pixels[dst + 3] = bgra[src + 3];
                }
            }
        }
    }

    fn present(&self, win: &NativeWindow) {
        if self.width == 0 || self.height == 0 {
            return;
        }
        let _ = win.set_buffers_geometry(
            self.width as i32,
            self.height as i32,
            Some(HardwareBufferFormat::R8G8B8A8_UNORM),
        );
        if let Ok(mut buf) = win.lock(None) {
            let dst_stride = buf.stride() * 4;
            let src_stride = (self.width as usize) * 4;
            let ptr = buf.bits() as *mut u8;
            for row in 0..(self.height as usize) {
                unsafe {
                    std::ptr::copy_nonoverlapping(
                        self.pixels.as_ptr().add(row * src_stride),
                        ptr.add(row * dst_stride),
                        src_stride,
                    );
                }
            }
        }
    }
}

fn keycode_text(code: Keycode, shift: bool) -> String {
    let ch = match code {
        Keycode::A => Some(if shift { 'A' } else { 'a' }),
        Keycode::B => Some(if shift { 'B' } else { 'b' }),
        Keycode::C => Some(if shift { 'C' } else { 'c' }),
        Keycode::D => Some(if shift { 'D' } else { 'd' }),
        Keycode::E => Some(if shift { 'E' } else { 'e' }),
        Keycode::F => Some(if shift { 'F' } else { 'f' }),
        Keycode::G => Some(if shift { 'G' } else { 'g' }),
        Keycode::H => Some(if shift { 'H' } else { 'h' }),
        Keycode::I => Some(if shift { 'I' } else { 'i' }),
        Keycode::J => Some(if shift { 'J' } else { 'j' }),
        Keycode::K => Some(if shift { 'K' } else { 'k' }),
        Keycode::L => Some(if shift { 'L' } else { 'l' }),
        Keycode::M => Some(if shift { 'M' } else { 'm' }),
        Keycode::N => Some(if shift { 'N' } else { 'n' }),
        Keycode::O => Some(if shift { 'O' } else { 'o' }),
        Keycode::P => Some(if shift { 'P' } else { 'p' }),
        Keycode::Q => Some(if shift { 'Q' } else { 'q' }),
        Keycode::R => Some(if shift { 'R' } else { 'r' }),
        Keycode::S => Some(if shift { 'S' } else { 's' }),
        Keycode::T => Some(if shift { 'T' } else { 't' }),
        Keycode::U => Some(if shift { 'U' } else { 'u' }),
        Keycode::V => Some(if shift { 'V' } else { 'v' }),
        Keycode::W => Some(if shift { 'W' } else { 'w' }),
        Keycode::X => Some(if shift { 'X' } else { 'x' }),
        Keycode::Y => Some(if shift { 'Y' } else { 'y' }),
        Keycode::Z => Some(if shift { 'Z' } else { 'z' }),
        Keycode::Keycode0 => Some('0'),
        Keycode::Keycode1 => Some('1'),
        Keycode::Keycode2 => Some('2'),
        Keycode::Keycode3 => Some('3'),
        Keycode::Keycode4 => Some('4'),
        Keycode::Keycode5 => Some('5'),
        Keycode::Keycode6 => Some('6'),
        Keycode::Keycode7 => Some('7'),
        Keycode::Keycode8 => Some('8'),
        Keycode::Keycode9 => Some('9'),
        Keycode::Space => Some(' '),
        Keycode::Period => Some('.'),
        Keycode::Minus => Some(if shift { '_' } else { '-' }),
        _ => None,
    };
    ch.map(|c| c.to_string()).unwrap_or_default()
}

fn load_wasm_bytes(app: &AndroidApp) -> Result<Vec<u8>, String> {
    if let Some(ext) = app.external_data_path() {
        let override_path = ext.join("launcher.wasm");
        if let Ok(bytes) = std::fs::read(&override_path) {
            log::info!("Loaded override wasm from {}", override_path.display());
            return Ok(bytes);
        }
    }
    let mgr = app.asset_manager();
    let cname = CString::new("launcher.wasm").unwrap();
    let mut asset = mgr
        .open(&cname)
        .ok_or_else(|| "assets/launcher.wasm not found".to_owned())?;
    asset
        .buffer()
        .map(|b| b.to_vec())
        .map_err(|e| format!("reading assets/launcher.wasm: {e}"))
}

fn load_component(engine: &Engine, app: &AndroidApp) -> Result<Component, String> {
    let bytes = load_wasm_bytes(app)?;
    let hash = format!("{:x}", Sha256::digest(&bytes));
    let cache_dir = app
        .internal_data_path()
        .unwrap_or_else(|| PathBuf::from("/data/local/tmp"));
    let cache_file = cache_dir.join(format!("launcher-{}.cwasm", &hash[..16]));
    if let Ok(cwasm) = std::fs::read(&cache_file) {
        if let Ok(comp) = unsafe { Component::deserialize(engine, &cwasm) } {
            log::info!("Loaded cached AOT component {}", cache_file.display());
            return Ok(comp);
        }
    }
    let comp = Component::from_binary(engine, &bytes).map_err(|e| format!("compile wasm: {e}"))?;
    if let Ok(serialized) = comp.serialize() {
        let _ = std::fs::write(&cache_file, serialized);
    }
    Ok(comp)
}

#[no_mangle]
fn android_main(app: AndroidApp) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("ultra-wasm"),
    );

    if let Ok(mut w) = WAKER.lock() {
        *w = Some(app.create_waker());
    }

    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr() as *mut _) }.expect("JavaVM");
    let activity = {
        let env = vm.attach_current_thread().expect("JNI attach");
        let obj = unsafe { JObject::from_raw(app.activity_as_ptr() as *mut _) };
        env.new_global_ref(obj).expect("global ref")
    };

    let data_dir = app
        .internal_data_path()
        .unwrap_or_else(|| PathBuf::from("/data/local/tmp"));
    let _ = std::fs::create_dir_all(&data_dir);

    let mut cfg = Config::new();
    cfg.wasm_component_model(true);
    let engine = Engine::new(&cfg).expect("wasmtime engine");
    let component = match load_component(&engine, &app) {
        Ok(c) => c,
        Err(e) => {
            log::error!("Failed to load launcher.wasm: {e}");
            return;
        }
    };

    let mut linker: Linker<HostState> = Linker::new(&engine);
    wasmtime_wasi::p2::add_to_linker_sync(&mut linker).expect("wasi p2 linker");
    latha::gui::host::add_to_linker::<_, HasSelf<_>>(&mut linker, |s| s).expect("gui host linker");
    ultra::launcher::android::add_to_linker::<_, HasSelf<_>>(&mut linker, |s| s)
        .expect("android host linker");

    let mut wasi_builder = WasiCtxBuilder::new();
    wasi_builder.inherit_stdio();
    wasi_builder.env("QT_QPA_PLATFORM", "qt2wasmgui");
    wasi_builder.env("LATHA_GUI_TEXT_INPUT", "1");
    let _ = wasi_builder.preopened_dir(&data_dir, "/data", FsPerms::ReadWrite);

    let state = HostState {
        wasi: wasi_builder.build(),
        table: ResourceTable::new(),
        vm,
        activity,
        frame_wanted: true,
        tick_at: None,
        quit: None,
        clipboard: String::new(),
        primary: String::new(),
        dark: true,
    };

    let mut store = Store::new(&engine, state);
    let gui = Gui::instantiate(&mut store, &component, &linker).expect("instantiate launcher");
    if let Err(e) = gui
        .latha_gui_app()
        .call_start(&mut store, &["ultra-home".to_owned()])
    {
        log::error!("app.start failed: {e}");
        return;
    }

    let mut fb = FrameBuf::new();
    let mut last_paint = Instant::now() - MIN_FRAME;
    let mut running = true;

    while running && store.data().quit.is_none() {
        let wait = store
            .data()
            .tick_at
            .map(|t| t.saturating_duration_since(Instant::now()))
            .unwrap_or(Duration::from_millis(50));

        app.poll_events(Some(wait.min(Duration::from_millis(50))), |ev| match ev {
            PollEvent::Main(MainEvent::Destroy) => running = false,
            PollEvent::Main(MainEvent::InitWindow { .. })
            | PollEvent::Main(MainEvent::WindowResized { .. }) => {
                if let Some(win) = app.native_window() {
                    let w = win.width().max(1) as u32;
                    let h = win.height().max(1) as u32;
                    let scale = (app.config().density().unwrap_or(420) as f64) / 160.0;
                    fb.resize(w, h);
                    let _ = gui.latha_gui_app().call_resize(&mut store, w, h, scale);
                    store.data_mut().frame_wanted = true;
                }
            }
            PollEvent::Main(MainEvent::GainedFocus) => {
                let _ = gui.latha_gui_app().call_focus(&mut store, true);
            }
            PollEvent::Main(MainEvent::LostFocus) => {
                let _ = gui.latha_gui_app().call_focus(&mut store, false);
            }
            PollEvent::Main(MainEvent::InputAvailable) => {
                if let Ok(mut iter) = app.input_events_iter() {
                    loop {
                        let read = iter.next(|event| {
                            match event {
                                InputEvent::MotionEvent(me) => {
                                    let p = me.pointer_at_index(0);
                                    let x = p.x() as f64;
                                    let y = p.y() as f64;
                                    let kind = match me.action() {
                                        MotionAction::Down => Some((
                                            wt::PointerKind::Press,
                                            wt::Button::Left,
                                            wt::Buttons::LEFT,
                                        )),
                                        MotionAction::Up => Some((
                                            wt::PointerKind::Release,
                                            wt::Button::Left,
                                            wt::Buttons::empty(),
                                        )),
                                        MotionAction::Move => Some((
                                            wt::PointerKind::Move,
                                            wt::Button::None,
                                            wt::Buttons::LEFT,
                                        )),
                                        MotionAction::Cancel => Some((
                                            wt::PointerKind::Leave,
                                            wt::Button::None,
                                            wt::Buttons::empty(),
                                        )),
                                        _ => None,
                                    };
                                    if let Some((kind, button, buttons)) = kind {
                                        let pe = wt::PointerEvent {
                                            kind,
                                            button,
                                            buttons,
                                            modifiers: wt::Modifiers::empty(),
                                            x,
                                            y,
                                            clicks: 1,
                                        };
                                        let _ = gui.latha_gui_app().call_pointer(&mut store, pe);
                                        store.data_mut().frame_wanted = true;
                                    }
                                    InputStatus::Handled
                                }
                                InputEvent::KeyEvent(ke) => {
                                    let pressed = ke.action() == KeyAction::Down;
                                    let keysym = match ke.key_code() {
                                        Keycode::Del => 0xff08,
                                        Keycode::Enter => 0xff0d,
                                        Keycode::Escape => 0xff1b,
                                        _ => 0,
                                    };
                                    let text = if pressed {
                                        keycode_text(ke.key_code(), ke.meta_state().shift_on())
                                    } else {
                                        String::new()
                                    };
                                    if keysym != 0 || !text.is_empty() {
                                        let ev = wt::KeyEvent {
                                            pressed,
                                            repeat: ke.repeat_count() > 0,
                                            keysym,
                                            scancode: ke.scan_code() as u32,
                                            modifiers: wt::Modifiers::empty(),
                                            text,
                                        };
                                        let _ = gui.latha_gui_app().call_key(&mut store, &ev);
                                        store.data_mut().frame_wanted = true;
                                    }
                                    InputStatus::Unhandled
                                }
                                _ => InputStatus::Unhandled,
                            }
                        });
                        if !read {
                            break;
                        }
                    }
                }
            }
            _ => {}
        });

        let has_events = EVENTS.lock().map(|q| !q.is_empty()).unwrap_or(false);
        let due = store
            .data()
            .tick_at
            .map(|t| Instant::now() >= t)
            .unwrap_or(false);
        if due || has_events {
            store.data_mut().tick_at = None;
            if let Ok(next) = gui.latha_gui_app().call_tick(&mut store) {
                if let Some(ms) = next {
                    store.data_mut().tick_at = Some(Instant::now() + Duration::from_millis(ms as u64));
                }
            }
        }

        if store.data().frame_wanted && last_paint.elapsed() >= MIN_FRAME {
            store.data_mut().frame_wanted = false;
            last_paint = Instant::now();
            if let Some(win) = app.native_window() {
                if let Ok(dmg) = gui.latha_gui_app().call_paint(&mut store) {
                    fb.apply_damage(&mut store, &gui, &dmg);
                    fb.present(&win);
                }
            }
        }
    }
}
