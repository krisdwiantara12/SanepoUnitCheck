# Sanepo Unit Check — Design Direction

## Design Read
Field diagnostic console for second-hand smartphone buyers and repair technicians, in a high-contrast Neo-Brutalist Dark Instrument style.
Dial: ENERGY 2 / RHYTHM 2 / MOTION 1.

## Purpose & Visual Rules
- **Palette (Max 2 core neutrals + 1 accent + semantic status):**
  - Background: `#0A0C0E` (Matte Charcoal — low outdoor reflection during COD inspection, true dark contrast for screen inspection)
  - Surface Primary: `#14181D` with sharp `1dp` border `#2E353F` (structure via crisp borders, no soft shadows or glassmorphism)
  - Surface Control Well: `#1C2229` with `1dp` border `#3A434F`
  - Text Primary: `#F4F6F8` (16:1+ contrast)
  - Text Secondary: `#B8C2CC` (9:1+ contrast)
  - Text Muted Label: `#95A2B0` (6:1+ contrast, WCAG AA pass)
  - Single Deliberate Accent: `#FF6B00` (Industrial Amber-Orange, dark `#0A0C0E` text on accent button; `#FF8533` highlight on dark)
  - Functional Diagnostic Status Only (always paired with explicit `PASS` / `FAIL` / `WARN` / `READY` text):
    - Pass: `#4ADE80` text on `#0D2B18` surface
    - Fail: `#F87171` text on `#331114` surface
    - Warn: `#FBBF24` text on `#2D2209` surface
- **Typography:**
  - System Sans (`sans-serif` / `sans-serif-medium`) for readable technical labels and controls.
  - Monospace (`monospace`, tabular digits) strictly for live hardware telemetry values (MHz, mA, Watts, `(X,Y)` coordinates, Widevine/Baseband/Board IDs) to prevent numeric jitter.
- **Shape & Motion:**
  - Sharp `2dp`–`4dp` radii throughout (no pill-everything).
  - Zero decorative gradients, zero glow orbs, zero emoji in UI, zero continuous pulsing loops (`MOTION 1` — instant state transitions and direct tap feedback only).
- **Hierarchy & Rhythm (`RHYTHM 2`):**
  - Dominant top Command Deck (Auto-Scan trigger + real-time PASS/WARN/FAIL counter + Share Report CTA).
  - Compact Device & Security Identity strip (OEM Commercial Name, SoC/GPU, RAM/Storage, Bootloader/Root/Widevine).
  - Grouped Diagnostic Suites separated by real technician workflow (Display & Digitizer, Battery & Charging, Audio & Haptics, Cameras & Flash, Sensors & Biometrics, RF / Ports & Keys), each with real Ready / Loading / Error / Result states.
