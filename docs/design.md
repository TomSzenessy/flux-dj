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
| `surface` | `#05070A` | app background |
| `panel` | `#0D1117` at 92% | panels and sheets |
| `line` | `#1F2937` | separators, inactive track |
| `text` | `#E5E7EB` | primary text |
| `textMuted` | `#94A3B8` | labels, secondary values |
| `deckA` | `#22D3EE` | Deck A accent, waveform high band |
| `deckB` | `#F472B6` | Deck B accent, waveform low band |
| `sync` | `#FBBF24` | sync, beat FX, armed states |
| `ok` | `#34D399` | harmonic match, recording armed |
| `danger` | `#F87171` | clip, destructive action |
| `waveLow` | `#F472B6` | waveform low band |
| `waveMid` | `#FBBF24` | waveform mid band |
| `waveHigh` | `#67E8F9` | waveform high band |

- **Type:** one sans-serif (system default) with tabular figures for BPM, time,
  key and gain; deck labels in small caps; no display face.
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
overlay); the performance surface never unmounts.

## Accessibility

- Minimum touch target 48 dp; jog and platters accept a larger hit area than
  their drawn circle.
- Colour is never the only signal: cue states also change shape/weight, the
  sync state has a label, and meter clipping has a text warning.
- Content descriptions on every icon-only control; the deck label announces the
  loaded track, BPM and key for screen readers.
- Text contrast at least 4.5:1 on the tokens above; large numeric readouts at
  least 3:1.

## Review oracle

A judged screenshot review at tablet and phone landscape sizes (light and dark
where the system supports it), plus the on-device session in
[`../VISION.md`](../VISION.md). `make ui-review` captures the screenshots.
