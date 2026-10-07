import "./style.css";
import { getCurrentWindow } from "@tauri-apps/api/window";
import * as api from "./api";
import type { Task } from "./api";
import { createSync } from "./sync";

const COLORS = ["blue", "navy", "green", "pink", "yellow", "purple"];
const REFRESH_MS = 60_000;

const $ = <T extends HTMLElement>(selector: string) => document.querySelector<T>(selector)!;

let tasks: Task[] = [];
let pending = 0;
let filterTag: string | null = null;
let editingId: string | null = null;
let doneOpen = false;

const ESCAPES: Record<string, string> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
};
const esc = (text: string) => text.replace(/[&<>"']/g, (c) => ESCAPES[c]);

/** Deadline metni saat dilimsiz yazıldığı için "şimdi" de aynı çerçevede alınır. */
const nowNaive = () => Date.now() - new Date().getTimezoneOffset() * 60_000;

function row(t: Task): string {
  const overdue = !t.done && t.deadlineKey !== null && t.deadlineKey < nowNaive();
  const unreadable = t.deadline !== "" && t.deadlineKey === null;
  const deadline = t.deadline
    ? `<span class="deadline${overdue ? " overdue" : ""}${unreadable ? " unreadable" : ""}">${esc(t.deadline)}</span>`
    : "";
  const tags = t.tags.map((g) => `<span class="tag">#${esc(g)}</span>`).join("");
  return `<div class="task c-${esc(t.color)}${t.done ? " done" : ""}" data-id="${esc(t.id)}">
    <div class="line">
      <input type="checkbox" class="check" ${t.done ? "checked" : ""} aria-label="Yapıldı" />
      <div class="body">
        <span class="title">${esc(t.title)}</span>${deadline}${tags ? `<span class="tags">${tags}</span>` : ""}
      </div>
      <button class="del" title="Sil" aria-label="Sil">&#215;</button>
    </div>
    ${editingId === t.id ? editor(t) : ""}
  </div>`;
}

function editor(t: Task): string {
  const recurrence = (value: string, label: string) =>
    `<option value="${value}"${(t.recurrence ?? "") === value ? " selected" : ""}>${label}</option>`;
  const swatches = COLORS.map(
    (c) =>
      `<button type="button" class="swatch c-${c}${t.color === c ? " on" : ""}" data-color="${c}" title="${c}" aria-label="${c}"></button>`,
  ).join("");
  return `<div class="editor">
    <input data-field="title" value="${esc(t.title)}" placeholder="Başlık" />
    <textarea data-field="notes" rows="3" placeholder="Not">${esc(t.notes)}</textarea>
    ${t.recurring ? "" : `<input data-field="deadline" value="${esc(t.deadline)}" placeholder="GG.AA.YYYY SS:DD" />`}
    <input data-field="tags" value="${esc(t.tags.join(", "))}" placeholder="etiketler, virgülle ayır" />
    <select data-field="recurrence">
      ${recurrence("", "Tekrar yok")}${recurrence("daily", "Her gün")}${recurrence("weekly", "Her hafta")}
    </select>
    <div class="swatches">${swatches}</div>
  </div>`;
}

function section(title: string, items: Task[]): string {
  return `<section>${title ? `<h2>${title}</h2>` : ""}${items.map(row).join("")}</section>`;
}

function renderFilters() {
  const all = [...new Set(tasks.flatMap((t) => t.tags))].sort((a, b) => a.localeCompare(b, "tr"));
  if (filterTag !== null && !all.includes(filterTag)) filterTag = null;
  $("#filters").innerHTML = all.length
    ? [`<button class="chip${filterTag === null ? " on" : ""}" data-tag="">Tümü</button>`]
        .concat(
          all.map(
            (g) =>
              `<button class="chip${filterTag === g ? " on" : ""}" data-tag="${esc(g)}">#${esc(g)}</button>`,
          ),
        )
        .join("")
    : "";
}

interface FocusState {
  field: string;
  start: number | null;
  end: number | null;
}

function captureFocus(): FocusState | null {
  const el = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
  const field = el?.dataset?.field;
  if (!el || !field || !el.closest(".editor")) return null;
  let start: number | null = null;
  let end: number | null = null;
  try {
    start = el.selectionStart;
    end = el.selectionEnd;
  } catch {
    // select gibi seçimi olmayan öğeler
  }
  return { field, start, end };
}

function restoreFocus(state: FocusState | null) {
  if (!state || editingId === null) return;
  const el = document.querySelector<HTMLInputElement>(
    `.task[data-id="${CSS.escape(editingId)}"] [data-field="${state.field}"]`,
  );
  if (!el) return;
  el.focus();
  try {
    if (state.start !== null && state.end !== null) el.setSelectionRange(state.start, state.end);
  } catch {
    // seçimi olmayan öğeler
  }
}

function render() {
  const focus = captureFocus();
  renderFilters();

  const visible = filterTag === null ? tasks : tasks.filter((t) => t.tags.includes(filterTag!));
  const recurring = visible.filter((t) => t.recurring);
  const open = visible.filter((t) => !t.recurring && !t.done);
  const done = visible.filter((t) => !t.recurring && t.done);

  $("#list").innerHTML = [
    recurring.length ? section("Tekrarlayan", recurring) : "",
    open.length ? section(recurring.length ? "Görevler" : "", open) : "",
    done.length
      ? `<details class="done-group"${doneOpen ? " open" : ""}><summary>Yapılanlar (${done.length})</summary>${done.map(row).join("")}</details>`
      : "",
    visible.length
      ? ""
      : `<p class="empty">${tasks.length ? "Bu etikette görev yok." : "Henüz görev yok."}</p>`,
  ].join("");

  renderFooter();
  restoreFocus(focus);
}

async function refresh() {
  [tasks, pending] = await Promise.all([api.listTasks(), api.pendingCount()]);
  render();
}

const clock = (ms: number) =>
  new Date(ms).toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" });

/** Alt çubuk: bekleyen sayısı, senkron sonucu ve Kaydet / giriş düğmesi. */
function renderFooter() {
  const s = sync.state;
  $("#pending").textContent = pending ? `Bekleyen: ${pending}` : "Bekleyen yok";

  let text: string;
  let isError = false;
  if (!s.configured) {
    text = "Google ayarı yok";
  } else if (s.signingIn) {
    text = "Tarayıcıda giriş yapılıyor…";
  } else if (!s.signedIn) {
    text = s.error ?? "Giriş yapılmadı";
    isError = s.error !== null;
  } else if (s.syncing) {
    text = "Kaydediliyor…";
  } else if (s.error) {
    text = `Hata: ${s.error}`;
    isError = true;
  } else if (s.at) {
    text = `Son senkron ${clock(s.at)} ✓`;
  } else {
    text = "Henüz senkronlanmadı";
  }

  const status = $("#sync-status");
  status.textContent = text;
  status.title = s.configError ?? s.error ?? "";
  status.classList.toggle("error", isError);

  const save = $<HTMLButtonElement>("#save");
  save.textContent = s.signingIn
    ? "Giriş yapılıyor…"
    : !s.signedIn
      ? "Google ile giriş"
      : s.syncing
        ? "Kaydediliyor…"
        : "Kaydet";
  save.disabled = !s.configured || s.signingIn || s.syncing;
  save.title = s.configured ? "" : (s.configError ?? "");
  $("#signout").hidden = !s.signedIn;
}

const sync = createSync({ onChange: renderFooter, onSynced: refresh });

/** Bir değişikliği uygular, sonra listeyi yeniler; hata kullanıcıyı engellemez. */
async function act(change: () => Promise<unknown>) {
  try {
    await change();
  } catch (e) {
    console.error(e);
  }
  await refresh();
  sync.notifyChange();
}

const taskId = (el: Element) => el.closest<HTMLElement>(".task")?.dataset.id ?? "";

function patchFor(field: string, value: string): api.TaskPatch | null {
  switch (field) {
    case "title":
      return value.trim() ? { title: value } : null;
    case "notes":
      return { notes: value };
    case "deadline":
      return { deadline: value };
    case "tags":
      return { tags: value.split(",") };
    case "recurrence":
      return value ? { recurring: true, recurrence: value } : { recurring: false };
    default:
      return null;
  }
}

const list = $("#list");

list.addEventListener("click", (e) => {
  const target = e.target as HTMLElement;
  const id = taskId(target);
  if (!id) return;

  if (target.closest(".del")) {
    if (editingId === id) editingId = null;
    void act(() => api.deleteTask(id));
  } else if (target.closest<HTMLElement>(".swatch")) {
    const color = target.closest<HTMLElement>(".swatch")!.dataset.color!;
    void act(() => api.updateTask(id, { color }));
  } else if (!target.closest(".editor, .check")) {
    editingId = editingId === id ? null : id;
    render();
  }
});

list.addEventListener("change", (e) => {
  const target = e.target as HTMLInputElement;
  const id = taskId(target);
  if (!id) return;

  if (target.classList.contains("check")) {
    void act(() => api.updateTask(id, { done: target.checked }));
    return;
  }
  const field = target.dataset.field;
  const patch = field ? patchFor(field, target.value) : null;
  if (patch) void act(() => api.updateTask(id, patch));
  else void refresh(); // geçersiz giriş (ör. boş başlık): eski değere dön
});

list.addEventListener("keydown", (e) => {
  const target = e.target as HTMLElement;
  if (!target.closest(".editor")) return;
  if (e.key === "Escape") {
    editingId = null;
    render();
  } else if (e.key === "Enter" && target.tagName === "INPUT") {
    (target as HTMLInputElement).blur();
  }
});

// `toggle` olayı kabarcıklanmaz; yakalama aşamasında dinlenir.
list.addEventListener(
  "toggle",
  (e) => {
    const el = e.target as HTMLElement;
    if (el.classList.contains("done-group")) doneOpen = (el as HTMLDetailsElement).open;
  },
  true,
);

$("#filters").addEventListener("click", (e) => {
  const chip = (e.target as HTMLElement).closest<HTMLElement>(".chip");
  if (!chip) return;
  const tag = chip.dataset.tag ?? "";
  filterTag = tag === "" || tag === filterTag ? null : tag;
  render();
});

$<HTMLFormElement>("#add-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const input = $<HTMLInputElement>("#add-input");
  const title = input.value.trim();
  if (!title) return;
  input.value = "";
  void act(() => api.addTask(title));
});

const appWindow = getCurrentWindow();
const autostartButton = $<HTMLButtonElement>("#autostart");

function renderAutostart(status: api.AutostartStatus) {
  autostartButton.hidden = !status.supported;
  autostartButton.classList.toggle("on", status.enabled);
  autostartButton.title = status.enabled
    ? "Windows açılışında otomatik başlıyor (kapatmak için tıkla)"
    : "Windows açılışında otomatik başlamıyor (açmak için tıkla)";
}

autostartButton.addEventListener("click", async () => {
  try {
    const current = await api.autostartStatus();
    await api.setAutostart(!current.enabled);
  } catch (e) {
    console.error(e);
  }
  renderAutostart(await api.autostartStatus());
});
void api.autostartStatus().then(renderAutostart);

$("#min").addEventListener("click", () => void appWindow.minimize());
$("#close").addEventListener("click", () => void appWindow.close());
$("#save").addEventListener("click", () => void (sync.state.signedIn ? sync.syncNow() : sync.signIn()));
$("#signout").addEventListener("click", () => void sync.signOut());

/** Kullanıcı düzenleme alanına yazarken yenileme yapılmaz; yazdığı kaybolmasın. */
const refreshWhenIdle = () => {
  if (!document.activeElement?.closest(".editor")) void refresh();
};
setInterval(refreshWhenIdle, REFRESH_MS);
window.addEventListener("focus", refreshWhenIdle);

void refresh();
void sync.init();
