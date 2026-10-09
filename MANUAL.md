# KinIRC Manual

Short and complete. No fluff.

## First run

1. Pick a language (once).
2. Create a profile: name, server, port, nick, ident, real name, channels.
3. Select it to connect. No default server: pick one at https://netsplit.de/networks/top100.php.


## Keys (chat)

- Up/Down: scroll one line (hold to accelerate).
- Left/Right: previous/next window.
- Fire (center, or 5): reply mode on channels — Up/Down picks a nick (shown white on blue), Fire or Left confirms and opens the editor with `nick ` ready. Back exits the mode.
- Left softkey: write. If the channel is not joined, it joins instead.
- Right softkey: menu.
- Star (*): clear window.
- Hash (#): close window. Title turns amber while closing; it deletes itself when the server confirms (20 s fallback).
- Back (SE key): jump to newest message.
- 0 key: window list. 1: Status. 7: memory/threads info.
- 3 key: held 200 ms: minimize to background.

## Menu

Nicks, Actions, Join, Windows, Clear, Channel list, Channel control (channels only), Interface, Ignores, Exit, Connect/Disconnect. In a private chat: Whois and Ignore/Unignore instead.

## Colors

- White: normal text. Light blue: normal nicks.
- Green: ops (@) and joins. 
- Yellow: voiced (+) nicks (nick part only, text stays white).
- Orange: messages mentioning you. Pink: "/me action"  or "Topic" or "Channel modes like "bans" or "server messages"
- Grey: App system lines. 
- Red: parts, errors, history-top mark.
- White title: You are inside the channel.
- Red title: not joined but window is open.
- Amber title: closing, it waits until server confirm. If impatient double click and it will close instantly but a few messages in transition might reopen it until server confirms you are out.

## Window

Top strip: dot (green connected / red offline), name of channel or private window, position (3/7) and optional bars representation.
the green dot gets an underline that blinks for 30 seconds then underline keeps there until you check the new status messages.

Bottom bar: write icon, clock, menu icon.

## Battery and background

Minimize with long press of button 3 or Menu > Minimize. There are Two modes that you can find at "Interface -> In Background):

- Power saving: PARTs every channel on minimize (windows kept, names in red), and re-JOINs on return. Least traffic, you miss nothing but appear to part/join. Keeps you connected so you can receive private messages but battery and battery last much much longer since network module has almost nothing to process, just a few pings to keep you connected.

- Keep active: stays joined, lines buffer up. More traffic, and more battery drain.


## Reconnect

If the server goes silent ~4 min or the socket breaks, it retries up to 10 times: 5 s, 10 s, 20 s, 30 s, then 60 s (about 7 min total), and rejoins your channels. Toggle in Interface.

## Channel events (Interface)

Two controls:

- Channel events: Hide all / Hide joins only / Show all.
- Join style (when shown): Compact fine (slim arrows), Compact Bold (bold arrows), Normal (full line with depart reason).

Hide all also hides topics, modes (including bans) and kicks — best for tiny screens. Hide joins only hides joins, parts, quits, nick changes and kicks, but keeps topics, channel modes and bans. Nick changes follow the join style (bold only in "Compact Bold").
Your own chat color follows your status (@ green, + yellow).

## Actions

Saved commands/macros (Menu > Actions > New, max 240 chars). Run one and it sends as-is. Useful for `/msg NickServ identify ...` or greetings.

## Ignores

Menu > Ignores: list of nicks whose messages are hidden. Toggle per query from its menu.
Mask only ignores the nickname. example: nick!*@*.*

## Channel list (/list)

Menu > Channel list downloads the whole server list (thousands of channels). On slow GPRS the connection saturates until it finishes. 
Only the first ~300 (configurable up to 5000) are kept on screen.
Avoid it on slow links; join with Menu > Join typing `#name` instead.

## Nick list

Menu > Nicks (channels only). Sorted with @ first. Kept live automatically; only requested from the server when empty.

## Alerts

Interface > Alerts: off, sound or vibration.
They fire only for windows you are not reading (or while minimized).
It fires on mentions on other windows or new private messages.

## Settings worth knowing

In order to minimize the use of battery, evalue to put the refresh rate has high as you are comfortable with (up to 1000ms), try to avoid stay on many channels of high activity, hide all the channel events, and enable the "power saving" mode, so when its minimized, it departs from all channels reducing to the network activity to a few pings per minute. Upper indicator margin moves the window counter and bars away from the top corner if your phone draws system icons there (signal, battery).
