"use strict";
const $ = selector => document.querySelector(selector);
let records = [], period = 7, kind = "all", query = "", fetching = false;
const live = new Set(["tv", "channel", "channels", "live", "iptv", "sport", "sports", "event", "events"]);
function category(row) { return live.has(row.type) ? "live" : row.type; }
function duration(ms) { const s = Math.floor(ms / 1000); return s >= 3600 ? `${Math.floor(s / 3600)}h ${Math.floor(s / 60) % 60}m` : s >= 60 ? `${Math.floor(s / 60)}m ${s % 60}s` : `${s}s`; }
function dayKey(ms) { const d = new Date(ms); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`; }
function dateLabel(ms) { const now = new Date(), yesterday = new Date(); yesterday.setDate(now.getDate() - 1); return dayKey(ms) === dayKey(+now) ? "Today" : dayKey(ms) === dayKey(+yesterday) ? "Yesterday" : new Date(ms).toLocaleDateString(undefined, {weekday:"short", day:"numeric", month:"short"}); }
function render() {
  const cutoff = new Date(); cutoff.setHours(0, 0, 0, 0); cutoff.setDate(cutoff.getDate() - period + 1);
  const selected = records.filter(row => row.started_at_ms >= +cutoff && (kind === "all" || category(row) === kind) && row.title.toLocaleLowerCase().includes(query));
  $("#total").textContent = duration(selected.reduce((sum, row) => sum + row.watched_ms, 0));
  $("#sessions").textContent = selected.length;
  const titles = new Set(selected.map(row => `${row.type}:${row.title}`)).size;
  $("#titles").textContent = `${titles} ${titles === 1 ? "title or channel" : "titles & channels"}`;
  $("#history").replaceChildren(); $("#empty").hidden = selected.length !== 0;
  let lastDay = null;
  for (const row of selected) {
    const day = dayKey(row.started_at_ms);
    if (day !== lastDay) { const heading = document.createElement("h3"); heading.className = "date-heading"; heading.textContent = dateLabel(row.started_at_ms); $("#history").append(heading); lastDay = day; }
    const element = $("#row").content.firstElementChild.cloneNode(true), type = category(row);
    const art = element.querySelector(".art"); art.classList.add(type === "series" ? "series" : type === "live" ? "live" : "movie"); art.textContent = type === "live" ? "◉" : type === "series" ? "▤" : "▷";
    element.querySelector("h3").textContent = row.title;
    const media = type === "live" ? "Live channel" : type === "series" ? "Series" : "Movie";
    const episode = Number.isInteger(row.season) ? ` · S${row.season} E${row.episode}` : "";
    element.querySelector(".details").textContent = `${media}${episode} · ${new Date(row.started_at_ms).toLocaleTimeString(undefined,{hour:"2-digit",minute:"2-digit"})}`;
    element.querySelector(".saved").textContent = row.finished ? "Session finished" : "Last saved session";
    element.querySelector(".duration strong").textContent = duration(row.watched_ms);
    $("#history").append(element);
  }
}
async function load() {
  if (fetching) return; fetching = true; $("#refresh").disabled = true;
  try {
    const response = await fetch("/api/reports", {cache:"no-store", credentials:"omit", redirect:"error"});
    if (!response.ok) {
      records = []; $("#history").replaceChildren(); $("#content").hidden = true; $("#locked").hidden = false;
      $("#locked p").textContent = response.status === 403 ? "Connect Tailscale on your iPhone and sign in with the same account as your computer, then try again." : "Your computer needs to be on with its keyring unlocked. Try again in a moment.";
      return;
    }
    const payload = await response.json(); if (document.hidden) { clearPrivateView(); return; } records = payload.records;
    $("#content").hidden = false; $("#locked").hidden = true;
    const notice = payload.demo ? "Sample reports · this preview uses generated examples." : payload.tv_update_pending ? "TV update pending. Your current installed TV app does not record these reports yet. The generated test clip verifies the connection." : "";
    $("#notice").textContent = notice; $("#notice").hidden = !notice;
    $("#updated").textContent = payload.collected_at_ms ? `Last collected ${new Date(payload.collected_at_ms).toLocaleString(undefined,{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"})} · Updates while your TV is reachable` : "No reports collected yet. New sessions will appear here after the next collection.";
    render();
  } catch { records = []; $("#history").replaceChildren(); $("#content").hidden = true; $("#locked").hidden = false; $("#locked p").textContent = "The private connection is unavailable. Keep Tailscale connected and your computer switched on, then try again."; }
  finally { fetching = false; $("#refresh").disabled = false; $("#loading").hidden = true; }
}
for (const button of document.querySelectorAll("[data-period]")) button.addEventListener("click", () => { period = Number(button.dataset.period); for (const b of document.querySelectorAll("[data-period]")) {b.classList.toggle("active",b === button);b.setAttribute("aria-pressed",String(b === button));} render(); });
for (const button of document.querySelectorAll("[data-type]")) button.addEventListener("click", () => { kind = button.dataset.type; for (const b of document.querySelectorAll("[data-type]")) {b.classList.toggle("active",b === button);b.setAttribute("aria-pressed",String(b === button));} render(); });
$("#search").addEventListener("input", event => { query = event.target.value.trim().toLocaleLowerCase(); render(); });
$("#refresh").addEventListener("click", load); $("#retry").addEventListener("click", load);
function clearPrivateView() { records = []; query = ""; $("#search").value = ""; $("#history").replaceChildren(); $("#total").textContent = "—"; $("#sessions").textContent = "—"; $("#titles").textContent = "Private view"; $("#updated").textContent = ""; }
document.addEventListener("visibilitychange", () => { if (!document.hidden) load(); else clearPrivateView(); });
window.addEventListener("pagehide", clearPrivateView);
setInterval(() => { if (!document.hidden) load(); }, 60000); load();
