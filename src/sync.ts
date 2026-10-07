import * as api from "./api";

/** Değişiklikten kaç ms sonra otomatik senkron başlar. */
const DEBOUNCE_MS = 5_000;
/** Pencere gün boyu açık kaldığından diğer cihazdaki değişiklikler için periyodik çekme. */
const PERIODIC_MS = 5 * 60_000;
/** Pencereye dönüldüğünde son senkrondan bu kadar geçtiyse yeniden senkronla. */
const FOCUS_MIN_GAP_MS = 60_000;
const RETRY_BASE_MS = 60_000;
const RETRY_MAX_MS = 15 * 60_000;

export interface SyncState {
  /** oauth.json okunabildi mi. */
  configured: boolean;
  configError: string | null;
  signedIn: boolean;
  signingIn: boolean;
  syncing: boolean;
  /** Son başarılı senkronun zamanı (ms). */
  at: number | null;
  error: string | null;
}

interface Hooks {
  onChange: () => void;
  /** Senkron bittikten sonra liste ve bekleyen sayısı yenilensin diye çağrılır. */
  onSynced: () => Promise<void>;
}

export function createSync({ onChange, onSynced }: Hooks) {
  const state: SyncState = {
    configured: false,
    configError: null,
    signedIn: false,
    signingIn: false,
    syncing: false,
    at: null,
    error: null,
  };
  let running = false;
  let again = false;
  let failures = 0;
  let timer: number | undefined;

  const schedule = (ms: number) => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => void syncNow(), ms);
  };

  const retryDelay = () => Math.min(RETRY_BASE_MS * 2 ** Math.max(0, failures - 1), RETRY_MAX_MS);

  async function loadAuth() {
    const auth = await api.authStatus();
    state.configured = auth.configured;
    state.configError = auth.configured ? null : auth.error;
    state.signedIn = auth.signedIn;
    if (auth.configured && auth.error) state.error = auth.error;
    onChange();
  }

  async function syncNow() {
    if (!state.signedIn) return;
    window.clearTimeout(timer);
    if (running) {
      again = true;
      return;
    }
    running = true;
    state.syncing = true;
    state.error = null;
    onChange();
    try {
      const report = await api.syncNow();
      state.at = report.at;
      failures = 0;
    } catch (e) {
      const message = String(e);
      if (message.startsWith("BUSY")) {
        again = true;
      } else if (message.startsWith("SIGNED_OUT")) {
        state.signedIn = false;
        state.error = "Oturum sona erdi, yeniden giriş yap";
      } else {
        state.error = message;
        failures += 1;
        schedule(retryDelay()); // internet gelince ya da sunucu düzelince kendiliğinden dener
      }
    } finally {
      running = false;
      state.syncing = false;
      onChange();
      await onSynced();
      if (again) {
        again = false;
        schedule(1_000);
      }
    }
  }

  async function signIn() {
    if (state.signingIn) return;
    state.signingIn = true;
    state.error = null;
    onChange();
    try {
      await api.signIn();
      await loadAuth();
    } catch (e) {
      state.error = String(e);
    } finally {
      state.signingIn = false;
      onChange();
    }
    if (state.signedIn) await syncNow();
  }

  async function signOut() {
    if (
      !window.confirm(
        "Çıkılsın mı? Bu cihazdaki görevler silinmez; yalnızca bu cihazda senkron durur. Diğer cihazlar etkilenmez.",
      )
    ) {
      return;
    }
    try {
      await api.signOut();
    } catch (e) {
      state.error = String(e);
    }
    state.at = null;
    await loadAuth();
  }

  return {
    state,
    syncNow,
    signIn,
    signOut,
    /** Yerelde bir değişiklik yapıldı: kısa süre sonra otomatik senkron. */
    notifyChange() {
      if (state.signedIn) schedule(DEBOUNCE_MS);
    },
    async init() {
      await loadAuth();
      if (state.signedIn) await syncNow();
      window.setInterval(() => void syncNow(), PERIODIC_MS);
      window.addEventListener("focus", () => {
        if (state.signedIn && Date.now() - (state.at ?? 0) > FOCUS_MIN_GAP_MS) void syncNow();
      });
    },
  };
}
