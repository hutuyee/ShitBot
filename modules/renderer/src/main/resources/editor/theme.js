"use strict";

(() => {
  const root = document.documentElement;
  let theme = "light";
  try {
    const saved = localStorage.getItem("shitbot-theme");
    if (saved === "light" || saved === "dark") theme = saved;
  } catch (_) { /* The editor remains usable when browser storage is unavailable. */ }

  function applyTheme(persist = false) {
    root.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", theme === "light" ? "#faf8f7" : "#1c1a20");
    const button = document.querySelector("#themeToggle");
    if (button) {
      const label = theme === "light" ? "切换为暗色主题" : "切换为亮色主题";
      button.querySelector("use").setAttribute("href", theme === "light" ? "#i-moon" : "#i-sun");
      button.setAttribute("aria-pressed", String(theme === "light"));
      button.setAttribute("aria-label", label);
      button.title = label;
    }
    if (persist) {
      try { localStorage.setItem("shitbot-theme", theme); } catch (_) { /* Keep the current page theme. */ }
    }
  }

  applyTheme();
  document.addEventListener("DOMContentLoaded", () => {
    applyTheme();
    document.querySelector("#themeToggle")?.addEventListener("click", () => {
      theme = theme === "light" ? "dark" : "light";
      applyTheme(true);
    });
  });
})();
