# Design direction

<!-- index: design | Visual direction, tokens, and UX principles | Before any UI work or a screenshot review. -->
<!-- covers: app/src/main/java/app/flux/dj/ui/** -->

FLUX keeps the performance surface visible and pushes everything else into a
sheet. The design is a dark instrument panel, not a generic Material app.

## Principles

- **The waveform is the interface.** Two stacked, colour-coded waveforms own the
  top third of the screen; the beat grid, cue flags and loop region are drawn on
  them rather than repeated in panels.
- **Minimal at rest, deep on demand.** Library, FX and Outputs are sheets.
  Nothing used while mixing is hidden behind a menu or a tab.
- **Multi-touch by design.** Every control owns its pointers, so a scratch on one
  deck can run while the crossfader moves and an EQ kill is held.
- **One grid, six faces.** Hot cue, loop, roll, jump, sampler and pitch play
  share one eight-pad grid; the mode chip changes the face, not the geometry.
- **Panel visibility is a preference.** Waveforms, overview, jogs, pads, loops,
  EQ, meters and the mixer can each be switched off from VIEW; the rest reflows
  and the choice is remembered. Four presets: Full, Mix, Scratch, Minimal.
- **Honest latency.** Anything Android cannot guarantee (Bluetooth delay, a
  second A2DP sink, USB-C routing) is stated in the Outputs sheet, not hidden.

## Tokens

| Token | Value | Use |
|---|---|---|
| `bg` | `#07080B` | app background |
| `s1` | `#0E1016` | panels and cards |
| `s2` | `#141722` | chips, insets |
| `s3` | `#1D2131` | pressed wells |
| `s4` | `#2A2F44` | raised thumb |
| `line` | white at 10% | separators, inactive outlines |
| `tx` | `#E8EBF2` | primary text |
| `mu` | `#7D849A` | labels, secondary values |
| `dim` | `#4A5068` | tertiary labels (large text only) |
| `a` | `#22D3EE` | Deck A accent |
| `b` | `#F472B6` | Deck B accent |
| `fx` | `#FBBF24` | sync, beat FX, armed states |
| `ok` | `#34D399` | harmonic match, recording armed |
| `bad` | `#EF4444` | clip, destructive action |
| `lo` | `#F43F5E` | waveform low band |
| `mid` | `#FBBF24` | waveform mid band |
| `hi` | `#E0F2FE` | waveform high band |

- **Type:** system sans for labels and controls; `FontFamily.Monospace` for BPM,
  time, key and gain so figures stay aligned; deck labels in small caps; no
  display face.
- **Shape:** 12 dp panels, 8 dp chips, 4 dp track backgrounds; 1 dp hairlines.
- **Spacing:** 4/8/12/16 dp grid; the mixer column is fixed-width so the
  waveform width changes with the panel layout, not with content.
- **Motion:** 120–180 ms ease-out for sheet and panel changes; no motion on
  waveform redraw or meters. Never animate a value the user is dragging.

## Screen states

Every screen ships these states: empty (no track loaded), loading (decoding,
playable after about 1 s), analysing (BPM/key pending), ready, error (decode or
network failure with a retry), and permission-denied (library or microphone).
Sheets close on the scrim and on Back (one `BackHandler` closes the topmost
overlay); dialog widths clamp to the screen and the Align sheet scrolls on a
phone. The performance surface never unmounts.

## Accessibility

- Primary controls keep a 48 dp touch area; the dense mixer chips use 32–40 dp
  with generous spacing, and closing that gap is tracked in issue #25. Jog and
  platters accept a larger hit area than their drawn circle.
- Colour is never the only signal: cue states also change shape/weight, the
  sync state has a label, and meter clipping has a text warning.
- Shared controls publish `Role.Button`/`Role.Switch` semantics and a label;
  icon-only controls have a content description, and the deck label announces
  the loaded track, BPM and key. A full TalkBack audit remains device work (#25).
- Text contrast at least 4.5:1 on the tokens above; large numeric readouts at
  least 3:1.

## Review oracle

A judged screenshot review at tablet and phone landscape sizes (light and dark
where the system supports it), plus the on-device session in
[`../VISION.md`](../VISION.md). `make ui-review` captures the screenshots once an Android host is available.
