import { invoke } from "@tauri-apps/api/core";

export interface Task {
  id: string;
  title: string;
  notes: string;
  color: string;
  tags: string[];
  deadline: string;
  /** Yazılan deadline okunabildiyse sıralama anahtarı (saat dilimsiz ms). */
  deadlineKey: number | null;
  recurring: boolean;
  recurrence: string | null;
  done: boolean;
  doneAt: number | null;
  updatedAt: number;
}

export interface TaskPatch {
  title?: string;
  notes?: string;
  color?: string;
  tags?: string[];
  deadline?: string;
  recurring?: boolean;
  recurrence?: string;
  done?: boolean;
}

export const listTasks = () =>
  invoke<Task[]>("list_tasks", { tzOffsetMin: new Date().getTimezoneOffset() });
export const addTask = (title: string) => invoke<string>("add_task", { task: { title } });
export const updateTask = (id: string, patch: TaskPatch) =>
  invoke<void>("update_task", { id, patch });
export const deleteTask = (id: string) => invoke<void>("delete_task", { id });
export const pendingCount = () => invoke<number>("pending_count");

export interface AutostartStatus {
  /** Yalnızca kurulu (release) sürümde etkin; geliştirme sürümünde gizlenir. */
  supported: boolean;
  enabled: boolean;
}

export const autostartStatus = () => invoke<AutostartStatus>("autostart_status");
export const setAutostart = (enabled: boolean) => invoke<void>("set_autostart", { enabled });

export interface AuthStatus {
  /** Google istemci bilgileri (oauth.json) okunabildi mi. */
  configured: boolean;
  signedIn: boolean;
  error: string | null;
}

export interface SyncReport {
  at: number;
  pulled: number;
  uploaded: boolean;
}

export const authStatus = () => invoke<AuthStatus>("auth_status");
export const signIn = () => invoke<void>("sign_in");
export const signOut = () => invoke<void>("sign_out");
export const syncNow = () => invoke<SyncReport>("sync_now");
