# Native Dialogs

DeluxeMenus can show **native Minecraft dialogs** alongside its inventory menus. A dialog is
configured in its own YAML file, addressed by a stable id, and opened with the `/dm dialog` command
family.

Dialogs are a first-class feature built directly on Paper's native dialog API. No other plugin is
required.

---

## 1. Compatibility

| Item | Value |
|---|---|
| Minimum server | **Paper / Minecraft 1.21.7** |
| Behaviour on 1.21.6 and older | DeluxeMenus logs a clear error and **disables itself** |
| Compiled against | `io.papermc.paper:paper-api:26.2.build.112-stable` |
| Verified API surface | Paper `1.21.7-R0.1-SNAPSHOT`, `1.21.8-R0.1-SNAPSHOT`, `26.2.build.112-stable` |

The floor is enforced twice: the plugin descriptor declares `api-version: '1.21.7'`, and
`DeluxeMenus#onEnable` runs a runtime version check as its very first action. The runtime check runs
before any dialog class is touched, so an unsupported server gets an actionable message instead of a
`NoClassDefFoundError`.

On a 1.21.6 server the console reports:

```
DeluxeMenus requires Paper/Minecraft 1.21.7 or newer because native dialogs are a core feature.
This server reports version '1.21.6-R0.1-SNAPSHOT'. DeluxeMenus will now disable.
Please update your server software.
```

Because native dialogs are core to the plugin, this is a deliberate breaking change: DeluxeMenus no
longer supports 1.21.6 or older. Everything else about existing GUI menus is unchanged.

---

## 2. Where dialogs live

```text
plugins/DeluxeMenus/
├── config.yml
├── gui_menus/      # inventory menus (unchanged)
└── gui_dialogs/    # native dialogs  <-- new
    ├── warp_confirm.yml
    ├── server_selector.yml
    ├── welcome_notice.yml
    └── feedback_form.yml
```

* The directory is created automatically on first start.
* Only `*.yml` files directly inside `gui_dialogs` are read. Sub-directories are ignored.
* One dialog definition per file.
* Dialogs are never read from `gui_menus`, and menu files are never reinterpreted as dialogs.

### Example dialogs are installed on a fresh server

On the very first start, when `gui_dialogs` does not exist yet, DeluxeMenus writes six example
dialogs into it — the same way the bundled example menus are written into `gui_menus`:

| File | Dialog id | Type | Demonstrates |
|---|---|---|---|
| `warp_confirm.yml` | `warp_confirm` | `confirmation` | Two buttons, the mapping and string action forms, `tooltip`, `width`, ordering |
| `welcome_notice.yml` | `welcome_notice` | `notice` | A single-button notice with a multi-line body |
| `server_selector.yml` | `server_selector` | `multi_action` | `columns`, `exit_button`, `player_command`, `console_command`, and `open_dialog` linking to another dialog |
| `feedback_form.yml` | `feedback_form` | `form` | All four input controls, `required`/`min_length`, `{input_id}` in actions, and `open_requirements` |
| `command_example.yml` | `command_example` | `multi_action` | `open_command` registering `/dmexample` with the alias `/dme` |
| `live_stats.yml` | `live_stats` | `multi_action` | Updating a dialog on demand with the `[refresh]` action, plus `[close]` |

Notes:

* This happens **once**, only when the directory is absent. Renaming or deleting an example is
  permanent — it is never restored.
* Files are written with "do not replace", so an example can never overwrite a file of your own.
* They are ordinary dialogs: edit them, or delete all six and start from scratch.
* `feedback_form` is the only one that is restricted, because it demonstrates `open_requirements`.
  It requires `deluxemenus.dialog.feedback`; operators bypass Bukkit permission checks and can always
  open it. Remove the `open_requirements` block (or grant the node) to open it for everyone else.
* `server_selector` runs `player_command: "spawn"` purely as an example. Point it at a command your
  server actually has, or the client will report an unknown command.
* `command_example` registers the command `/dmexample` (alias `/dme`). If that name is already taken
  on your server the command is skipped with a warning and the dialog stays reachable through
  `/dm dialog open command_example`.
* `live_stats` updates itself when its **Refresh** button is pressed and leaves escape working.
  No timer runs for it, so it costs nothing while it is open. See section 10 for how to add a timer,
  and for what that costs.

### Identity rules

| Rule | Detail |
|---|---|
| Source of the id | The required root `id` key. The filename is **not** an identifier. |
| Allowed characters | Letters, digits, `_` and `-` |
| Case | Ids are unique **case-insensitively**. Lookups are case-insensitive. |
| Duplicates | Rejected. When two files declare the same id, the file whose name sorts first alphabetically wins and the other file is reported as failed. Selection never depends on filesystem iteration order. |

---

## 3. Common schema

```yaml
id: warp_confirm              # required, unique, [A-Za-z0-9_-]
type: confirmation            # required: notice | confirmation | multi_action | form
title: "&6Confirm teleport"   # required
external_title: "Confirm"     # optional; used on buttons that open this dialog
permission: "my.permission"   # optional; checked against the viewer
open_command: [mydialog, md]  # optional; see section 12
open_requirements: []         # optional; see section 8
body: []                      # optional; see section 6
inputs: []                    # form only; see section 7
buttons: []                   # see section 5
options: {}                   # optional; see section 4
```

All text fields (`title`, `external_title`, `body[].content`, button `label`/`tooltip`, input
`label`, option `label`) accept legacy `&` colour codes, hex colours (`&#aaFF00`) and PlaceholderAPI
placeholders. Placeholders are resolved against the **viewer**.

### `options`

| Key | Type | Default | Applies to | Meaning |
|---|---|---|---|---|
| `pause` | boolean | `false` | all | Pause the game while open (single-player only) |
| `after_action` | `close` \| `none` \| `wait_for_response` | `close` | all | What the client does after dismissal |
| `columns` | 1–1024 | `2` | `multi_action`, `form` | Button grid columns |
| `refresh_interval` | 0–86400 | `0` | all | Seconds between automatic re-renders; `0` disables. **Setting this turns escape off for the dialog** — the two are one decision, not two options. See section 10. |
| `refresh_max_seconds` | 1–86400 | `300` | all | Safety bound on how long a refresh session may run without interaction. See section 10. |
| `exit_button` | button mapping | none | `multi_action`, `form` | Extra button rendered outside the grid |

Escape is **not** an option. It is on for every dialog that has no `refresh_interval`, and off for
every dialog that has one, because a timer cannot coexist with it (section 10).

`exit_button` is a full button mapping (`id`, `label`, `tooltip`, `width`, `actions`). It is ignored,
with a warning, for `notice` and `confirmation`.

---

## 4. Dialog types

Each config type maps to exactly one Paper API type. The mapping is deliberately one-to-one so that
behaviour is predictable.

| `type` | Paper API type | Buttons | Inputs | Use for |
|---|---|---|---|---|
| `notice` | `NoticeType` | **exactly 1** | not allowed | A message with a single acknowledgement |
| `confirmation` | `ConfirmationType` | **exactly 2** (first = yes, second = no) | not allowed | A yes/no decision |
| `multi_action` | `MultiActionType` | 1 or more | not allowed | Several independent choices |
| `form` | `MultiActionType` + inputs on the base | 1 or more | **1 or more required** | Collecting input from the player |

Aliases accepted for convenience: `confirm`/`server_confirmation`, `multiaction`/`server_multi_action`,
`server_form`/`dialog_form`, `server_notice`/`dialog_notice`.

Anything outside this table is rejected with a diagnostic that lists the valid values.

### Paper dialog types that are *not* exposed

Paper also exposes `DialogListType` and `ServerLinksType`. They are intentionally **not** offered as
config types: `dialog_list` needs a registry set of dialog instances rather than a config tree, and
`server_links` renders the server's link list with no configurable content. Exposing them as YAML
would only invent behaviour the API does not provide. They can be added later without breaking
existing files.

Similarly `ItemDialogBody` (an item body) is not exposed yet; only text bodies are supported.

---

## 5. Buttons

```yaml
buttons:
  - id: confirm                 # required, unique within the dialog, [A-Za-z0-9_-]
    label: "&aTeleport"         # required
    tooltip: "Go to spawn"      # optional (may contain placeholders)
    width: 120                  # optional, 1..1024, default 100
    validate_inputs: true       # optional, default true. See "Cancel and skip buttons" below.
    actions:                    # optional, ordered
      - type: player_command
        command: "spawn"
      - type: message
        message: "Teleporting..."
```

* Order is preserved exactly as written: Paper receives the buttons in file order.
* `id` must be unique within the dialog, compared case-insensitively.
* The pressed button is identified by the callback Paper invokes, **not** by any index or id sent by
  the client, so a modified client cannot invoke a button that does not exist or substitute another
  one.
* A button with no `actions` simply does nothing (useful for an acknowledgement button).

### Cancel and skip buttons

A form submits its values through **whichever button is pressed** — the API gives no way to tell a
"submit" button apart from a "cancel" button. So by default every button on a form validates the
inputs, and on a form with `required` fields a cancel button would always fail that check.

Set `validate_inputs: false` on any button that does not submit the form:

```yaml
buttons:
  - id: submit
    label: "&aSubmit"
    actions:
      - type: console_command
        command: "say %player_name% rated us {rating}/5"

  - id: skip
    label: "&7Not now"
    validate_inputs: false     # does not submit, so the required fields must not block it
    actions:
      - type: message
        message: "&7Maybe later."
```

Consequences, both intended:

* A button with `validate_inputs: false` is treated as **not submitting the form**. No validation
  runs, and **no input value is exposed to its actions** — `{input_id}` is not substituted, because
  accepting unvalidated client data is exactly what validation exists to prevent.
* The loader warns when a button validates but none of its actions read an input. That is a button
  that can only fail its own dialog's required fields and re-open the form, which is almost always
  a cancel button missing `validate_inputs: false`.

---

## 6. Body

```yaml
body:
  - type: text
    content: "You are about to teleport to &espawn&r."
    width: 200                  # optional, 1..1024, default 200
```

`type: text` is the only supported body element and renders as `PlainMessageDialogBody`.

---

## 7. Form inputs

Every input below maps to a real Paper input control. Fields that do not apply to the selected
`type` are rejected rather than silently ignored.

| `type` | Paper control | Response type | Resulting `{placeholder}` |
|---|---|---|---|
| `text` | `TextDialogInput` | string | the text |
| `boolean` | `BooleanDialogInput` | boolean | `true` / `false` |
| `single_option` | `SingleOptionDialogInput` | string | the option `value` |
| `number_range` | `NumberRangeDialogInput` | float | the number as text |

Aliases: `bool`/`checkbox`, `string`/`text_input`/`input`, `select`/`singleoption`/`radio`/`dropdown`,
`number`/`numberrange`/`range`/`slider`/`float`.

### Shared keys

| Key | Type | Applies to | Meaning |
|---|---|---|---|
| `id` | string | all | Required, unique within the dialog. This is the response key. |
| `type` | string | all | Required. |
| `label` | string | all | Required. |
| `label_visible` | boolean | `text`, `single_option` | Draw the label (default `true`). |
| `width` | 1–1024 | `text`, `single_option`, `number_range` | Rendered width (default `200`). |
| `initial` | type-specific | all | Starting value. Must match the type. |
| `required` | boolean | all | Reject an empty submission (default `false`). |

### `text`

| Key | Type | Meaning |
|---|---|---|
| `max_length` | integer ≥ 1 | Maximum length, default `32`. Enforced by the client **and** re-checked on submission. |
| `min_length` | integer ≥ 0 | Minimum length, checked on submission. |
| `multiline` | boolean | Allow multiple lines (default `false`). |
| `max_lines` | integer ≥ 1 | Only meaningful when `multiline` is true. |
| `height` | 1–512 | Only meaningful when `multiline` is true. |

### `boolean`

| Key | Type | Meaning |
|---|---|---|
| `on_true` | string | Template value used when true (default `"true"`). |
| `on_false` | string | Template value used when false (default `"false"`). |

### `single_option`

| Key | Type | Meaning |
|---|---|---|
| `options` | list | Required, at least one entry. |

Each option entry:

```yaml
options:
  - label: "Bug report"   # display text
    value: bug            # value reported back; `id` is accepted as an alias
    initial: false        # at most one entry may be true
```

### `number_range`

| Key | Type | Meaning |
|---|---|---|
| `start` | number | Required, inclusive lower bound. |
| `end` | number | Required, inclusive upper bound, must be greater than `start`. |
| `step` | number > 0 | Slider step. |
| `initial` | number | Must lie inside `start..end`. |
| `label_format` | string | Format string or translation key, for example `"%s: %s"`. |

---

## 8. Open requirements

`open_requirements` reuses **the exact same requirement engine as menus** and is evaluated against
the **viewer** every time the dialog is opened, including cross-player opens. Deny commands
configured on a requirement run as part of the check.

```yaml
open_requirements:
  - type: has permission
    permission: "my.permission"
  - type: has money
    amount: 500
```

The list form above is the documented shape. Each entry is a requirement mapping using the same
`type` names, options, `optional`, `success_commands` and `deny_commands` keys that menu requirements
use — see the main DeluxeMenus requirement documentation.

If the requirement block cannot be parsed, the dialog **fails closed**: it does not load, and the
reason is reported with the file, id and key path.

`deluxemenus.dialog.open.bypass` skips both `permission` and `open_requirements` for the viewer. It is
held by the **sender** of the command.

---

## 9. Actions

A button's `actions` list is ordered and every entry runs in the same way a menu item click action
runs, including `<delay=...>`, `<chance=...>`, `{...}` argument substitution and PlaceholderAPI.

Two forms are accepted.

### Mapping form

```yaml
- type: player_command
  command: "spawn"
  delay: "20"      # optional
  chance: "50"     # optional
```

### String form (identical to menu actions)

```yaml
- "[player] spawn"
- "[console] say hi <delay=20> <chance=50>"
```

### Action vocabulary

| Config `type` | Canonical DeluxeMenus action | Required payload key |
|---|---|---|
| `message` | `[message]` | `message` |
| `player_command` | `[player]` | `command` |
| `console_command` | `[console]` | `command` |
| `command_event` | `[commandevent]` | `command` |
| `broadcast` | `[broadcast]` | `message` |
| `actionbar` | `[actionbar]` | `message` |
| `minimessage` | `[minimessage]` | `message` |
| `minibroadcast` | `[minibroadcast]` | `message` |
| `chat` | `[chat]` | `message` |
| `log` | `[log]` | `message` |
| `json` | `[json]` | `message` |
| `jsonbroadcast` | `[jsonbroadcast]` | `message` |
| `open_menu` | `[openguimenu]` | `menu` |
| `close` | `[close]` | – (closes the dialog, not a menu — see section 10) |
| `refresh` | `[refresh]` | – (re-renders the dialog — see section 10) |
| `sound` | `[sound]` | `sound` |
| `rawsound` | `[rawsound]` | `sound` |
| `broadcastsound` | `[broadcastsound]` | `sound` |
| `broadcastrawsound` | `[broadcastrawsound]` | `sound` |
| `broadcastsoundworld` | `[broadcastsoundworld]` | `sound` |
| `broadcastrawsoundworld` | `[broadcastrawsoundworld]` | `sound` |
| `connect` | `[connect]` | `server` |
| `takemoney` | `[takemoney]` | `amount` |
| `givemoney` | `[givemoney]` | `amount` |
| `takeexp` | `[takeexp]` | `amount` |
| `giveexp` | `[giveexp]` | `amount` |
| `givepermission` | `[givepermission]` | `permission` |
| `takepermission` | `[takepermission]` | `permission` |
| `meta` | `[meta]` | `meta` |
| `placeholder` | `[placeholder]` | `placeholder` |
| `ephemeralcooldown` | `[ephemeralcooldown]` | `cooldown` |
| `open_dialog` | dialog subsystem | `dialog` |

`value` is accepted as an alias for the payload key in every mapping, so `- type: message` plus
`value: "hi"` also works.

Ordering and errors match existing menu behaviour: all actions are queued in order, and one action
failing does not abort the rest of the list. Each action reports its own problems.

### Inventory-specific actions

No action type in this version of DeluxeMenus needs clicked-slot, inventory, click-type or item data,
so the whole vocabulary above is available from a dialog. However two actions behave differently
outside an inventory:

* `refresh` needs an open DeluxeMenus menu. A dialog has none, so the action is **skipped**. The
  loader warns about this at load time.
* `placeholder` writes to the response-scoped holder, which is discarded once the actions finish.

No fake inventory or click context is ever fabricated.

### `open_dialog`

```yaml
- type: open_dialog
  dialog: some_other_dialog
```

The linked dialog is opened through the same pipeline as the command, so its `permission` and
`open_requirements` are evaluated against the player who pressed the button. A dialog may not link to
itself (rejected at load time). A link to an id that is not loaded produces a startup warning and
fails harmlessly when pressed.

Links are only ever triggered by an actual button press, so an `A -> B -> A` cycle is bounded by how
many times the player clicks.

---

## 10. Refreshing a dialog

A dialog **cannot be updated in place** — the native API only offers "show this dialog". Updating one
therefore means re-rendering it with freshly resolved placeholders and sending it again. There are
exactly two ways to trigger that, and they are mutually exclusive in a way described below.

### Refresh on demand (recommended)

The `refresh` action re-renders and re-sends the dialog the player is currently in:

```yaml
id: live_stats
type: multi_action
title: "&aYour stats"
body:
  - type: text
    content: "&7Health: &c%player_health%&7/&c%player_max_health%"
buttons:
  - id: refresh_now
    label: "&bRefresh"
    width: 150
    actions:
      - type: refresh        # re-render and re-send, right now
  - id: close
    label: "&7Close"
    width: 150
    actions:
      - type: close          # in a dialog, [close] dismisses the dialog
```

This is what the shipped `live_stats.yml` does. It needs no options at all, and it has no caveats:

* It is driven by a real button press, so the server knows the player is still there. The press is
  also what makes the click visible to the server in the first place.
* Escape keeps working, and nothing re-opens the dialog after the player presses it.
* It runs immediately, so `delay` and `chance` do not apply to it.

A dialog opened with `-p:<player>` keeps resolving its placeholders against that player on every
refresh.

Inside a **menu**, `[refresh]` keeps its original meaning: it re-evaluates the menu's placeholders and
requirements. The dialog behaviour only applies inside a dialog.

### A refresh timer (turns escape off)

A timer re-renders on an interval, without the player doing anything:

```yaml
options:
  refresh_interval: 2      # re-render every 2 seconds; 0 (the default) disables the timer. This
                           # single line also turns escape off for this dialog.
  refresh_max_seconds: 300 # safety bound: stop after 5 minutes with no interaction
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `options.refresh_interval` | 0–86400 | `0` | Seconds between refreshes. `0` renders once and never refreshes. Setting it turns escape off. |
| `options.refresh_max_seconds` | 1–86400 | `300` | Safety bound on how long a session keeps refreshing with no interaction. Must be at least `refresh_interval`. |

**A timer cannot coexist with escape, and this is not a plugin limitation you can work around.**
Minecraft never tells the server that a dialog was dismissed: there is no dialog close event, no
packet, and a dialog is not a container, so pressing escape produces *nothing* at all on the server.
The only packet a dialog ever produces is a button press. A blind timer would therefore keep
re-opening a dialog the player had already escaped from, every interval, for as long as
`refresh_max_seconds` allowed — and escape could never get rid of it.

So the two are a single decision, expressed by a single key. Setting `refresh_interval` is what turns
escape off; there is no separate flag to set, and therefore no way for the two to contradict each
other. A dialog with a timer has no invisible exit, which is exactly the condition the timer needs to
be safe: every way it can end is something the server observes.

A timer stops as soon as the player presses any button, opens any inventory (a menu, another plugin's
GUI, a chest — anything that replaces the screen), quits, dies, respawns, changes world, or when
`/dm reload` replaces the definition. Those are every route by which a dialog can disappear that the
server can actually observe.

Because escape is off in timer mode, a refreshing dialog **must** offer a button to leave by. Every
dialog type requires at least one button, so that is structural rather than something you have to
remember.

### What both forms share

Anything the viewer has typed is cleared, because a refresh re-sends the whole dialog. Refreshing is
for dialogs that *display* live data, not for forms. The loader warns if a dialog refreshes and also
declares inputs.

### Callback lifetime

Every render registers one callback per button with Paper, and each callback carries a lifetime. For a
dialog that re-renders on a timer that lifetime is tied to the refresh interval (the interval plus ten
seconds, never below thirty), so a superseded generation expires shortly after the next one replaces
it. The number of live callbacks therefore stays proportional to the number of open dialogs rather
than to the number of refreshes. Press-driven refreshes create far fewer generations — one per click
— so they keep the ordinary one-hour lifetime.

---

## 11. Input values in actions

Submitted values are exposed to the button's actions as `{input_id}`:

```yaml
- type: console_command
  command: "say %player_name% rated us {rating}/5 ({category})"
```

* Values are scoped to the single response being handled and are discarded afterwards.
* They are **never** registered as global placeholders, so one player's input can never leak into
  another player's text.
* Values are **not persisted** and are **not logged**. Only the id and the number of problems are
  logged when a submission is rejected.
* Because `{...}` substitution also happens inside action arguments, an input value containing
  `{something}` is not re-expanded into a further placeholder.
* A button with `validate_inputs: false` receives **no** input values at all — see section 5. It is
  treated as a button that does not submit the form, so there is nothing validated to expose.

---

## 12. Commands

```text
/dm dialog open <dialog> [player] [-p:<player>]
/dm dialog list
/dm dialog info <dialog>

/dm open-dialog <dialog> [player] [-p:<player>]   # alias for /dm dialog open
```

The existing `/dm open <menu> [player]` and every current alias (`/dm`, `/deluxemenu`, `/dmenu`) are
unchanged. Dialogs live under their own subcommand so a dialog id can never collide with a menu id.

| Behaviour | Detail |
|---|---|
| Self open | The sender must be a player. Console must supply `<player>`. |
| Target player | Requires `deluxemenus.dialog.open.others`. Resolved with an exact name lookup, matching the menu commands. |
| `-p:<player>` | Resolves placeholders for `title`, `body`, labels and tooltips against that player instead of the viewer. Requires `deluxemenus.placeholdersfor`; a target holding `deluxemenus.placeholdersfor.exempt` is refused. |
| Unknown id | `Could not find dialog: <dialog>.` |
| Offline player | `Player: <player> is not online!` |
| Permission failure | The dialog's own `permission` is checked against the **viewer**, never the sender. |
| Requirements failure | `open_requirements` are evaluated against the **viewer**. |

`/dm dialog list` prints ids in a deterministic, case-insensitive sorted order plus a total count.
`/dm dialog info <dialog>` prints the source file, type, version token, permission, open command,
requirement count, body/button/input counts and the dialog's options. It never prints player
submitted data.

### Tab completion

* `dialog` → `open`, `list`, `info` (only the ones the sender may use)
* `open` / `info` → dialog ids **the sender could open**, so restricted ids are not revealed
* after the id → online player names when the sender has `deluxemenus.dialog.open.others`, plus `-p:`

### Your own open command (`open_command`)

A dialog can register its own server command instead of always going through `/dm dialog open`:

```yaml
id: warp_confirm
type: confirmation
title: "&6Confirm teleport"
open_command: [warpconfirm, wc]   # first entry is the command, the rest are aliases
```

A single string works too: `open_command: warpconfirm`.

* Declaring `open_command` **is** the opt-in. Unlike menus there is no separate
  `register_command: true` flag, because a dialog has no other reason to name a command.
* Names are lower cased, because that is how Bukkit matches commands. Only lower case letters,
  digits, `_` and `-` are accepted; anything else is a configuration error.
* `/warpconfirm` opens the dialog for the sender (the sender must be a player).
* `/warpconfirm <player>` opens it for someone else, requiring
  `deluxemenus.dialog.open.others`. Player names are tab-completed.
* It runs the **same pipeline** as `/dm dialog open`: `deluxemenus.dialog.open`, the dialog's own
  `permission` and its `open_requirements` all still apply. There is no second set of rules to learn.
* These names are reserved and can never be claimed: `deluxemenus`, `dm`, `deluxemenu`, `dmenu`.

**Collisions are not fatal.** A name you claim is only actually registered if it is free at the time
the server loads. If vanilla, another plugin, a menu or another dialog already owns it, DeluxeMenus
logs a warning and simply does not register that command — the dialog still loads and stays reachable
through `/dm dialog open <id>`. Aliases are checked individually, so a taken alias is dropped with a
warning while the rest of the command keeps working.

On `/dm reload` every dialog command is unregistered and re-registered from the freshly loaded
definitions, so renaming a command takes effect immediately and a removed one disappears.

---

## 13. Permissions

All default to `op`.

| Node | Gates |
|---|---|
| `deluxemenus.dialog.open` | `/dm dialog open` and `/dm open-dialog` |
| `deluxemenus.dialog.open.others` | Targeting another player |
| `deluxemenus.dialog.open.bypass` | Skipping the dialog's `permission` and `open_requirements` for the viewer |
| `deluxemenus.dialog.list` | `/dm dialog list` |
| `deluxemenus.dialog.admin` | `/dm dialog info` |
| `deluxemenus.placeholdersfor` | The `-p:<player>` argument (shared with the menu commands) |
| `deluxemenus.placeholdersfor.exempt` | Exempt from being used as a placeholder target (shared) |

A dialog may additionally declare its own `permission`, checked against the viewer on every open. The
command permission gates the operation; a dialog's own permission is an **additional** requirement.
There is no implicit `deluxemenus.dialog.<id>` wildcard — the `permission` field is the single, explicit
way to restrict one dialog.

---

## 14. Reload

* `/dm reload` reloads the general config, **all menus and all dialogs**.
* `/dm reload <menu>` reloads one menu only, exactly as before.
* Reloading deletes nothing from disk and never requires a restart.

Semantics:

1. Every `gui_dialogs/*.yml` file is read and validated into a candidate set.
2. The registry is **replaced in one step**. Callers never observe a half-loaded state.
3. Valid files become active immediately. Invalid files are reported and skipped — one broken file
   does not take down unrelated dialogs.
4. A file that was deleted has its dialog **unregistered**; no stale entry is left behind.
5. A dialog that was open when the reload happened keeps its client-side view, but pressing one of its
   buttons is **rejected** with `The dialog <dialog> has been reloaded since it was opened.` Any
   actions from the replaced definition are not run.

The count of loaded dialogs is reported back:

```
DeluxeMenus successfully reloaded!
12 menus loaded...
4 dialogs loaded...
```

and, when any file failed:

```
2 dialog file(s) failed validation, see the console for details.
```

---

## 15. Diagnostics

Every problem is reported once, with the file, the dialog id, the key path and a short fix hint:

```
[error] feedback_form.yml (dialog 'feedback_form') at 'inputs[1].options': single_option inputs need
at least one option. Hint: Add an 'options' list with 'id' and 'label' entries.
```

Severities:

| Severity | Effect |
|---|---|
| `error` | The file is not loaded. Everything else still loads. |
| `warning` | The file loads; the noted key is ignored or has a caveat. |

Errors are emitted for anything that stops a file loading: missing or malformed required fields,
unknown types, an out of range value, an unknown action, a requirement block that cannot be parsed, and
the one combination that cannot work — `refresh_max_seconds` shorter than `refresh_interval`
(section 10).

Warnings are emitted for: an `inputs` block on a non-form type, an `exit_button` on `notice`/
`confirmation`, `max_lines`/`height` without `multiline`, a dialog that both refreshes and collects
input (refreshing clears what the viewer typed), a leftover `can_close_with_escape` key (removed —
escape now follows `refresh_interval`), an `open_dialog` target that is not loaded, and a button that
validates inputs without reading any of them (which is a cancel button missing
`validate_inputs: false`).

---

## 16. Limits

| Limit | Value | Source |
|---|---|---|
| Button/label width | 1–1024 | Paper API |
| Body width | 1–1024 | Paper API |
| Multiline height | 1–512 | Paper API |
| `columns` | 1–1024 | Paper API |
| Text `max_length` | ≥ 1 (default 32) | Paper API |
| Buttons per dialog | type-dependent: 1 for `notice`, 2 for `confirmation`, otherwise at least 1 and at most 64 | DeluxeMenus validation |
| Dialog ids, button ids, input ids | letters, digits, `_`, `-`; unique within their scope | DeluxeMenus validation |
| Button callback lifetime | 1 hour, single use | DeluxeMenus |

---

## 17. Known limitations

These are deliberate and are stated rather than silently worked around:

1. **Minimum 1.21.7.** Native dialogs do not exist below that version, and DeluxeMenus disables itself.
2. **No `dialog_list` / `server_links` config types.** See section 4 for the reason.
3. **Text bodies only.** Item bodies (`ItemDialogBody`) are not exposed as YAML yet.
4. **A dismissed dialog cannot be detected, so escape and a refresh timer are mutually exclusive.**
   The only packet a dialog produces is a button press; there is no close event, no close packet, and
   a dialog is not a container, so pressing escape reaches the server as nothing at all. A timer
   therefore cannot know the dialog is gone and would keep re-opening it. The two are expressed as one
   decision rather than two options: `refresh_interval` is what turns escape off, so nothing can be
   configured into an inconsistent state and `can_close_with_escape` no longer exists as a key. To
   keep escape and still update a dialog, drive updates from a `[refresh]` button instead of a timer.
   See section 10.
5. **`required` and `min_length` are validated on submission, not by the client.** Paper lets a form be
   submitted with an empty optional-looking field, so the server re-checks and, on failure, explains
   the problem and re-opens a freshly bound dialog so the player can correct it. Input values are not
   restored into the reopened dialog, and the player can always leave with escape (which is on by
   default). A cancel button must set `validate_inputs: false`, or it will be rejected by required
   fields it never uses — see section 5.
6. **Input values are not exposed as PlaceholderAPI placeholders.** They are available to actions as
   `{input_id}` only. This is intentional: a global placeholder would be shared mutable state between
   players.
7. **A reload invalidates callbacks for dialogs that were already open** (section 14, point 5).
8. **Cross-player opens bind actions to the target viewer**, not the sender. This matches the menu
   commands: `/dm dialog open <dialog> <player>` runs the dialog and its actions as that player.
