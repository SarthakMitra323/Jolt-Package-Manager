(() => {
  // ---- Copy to Clipboard + Toast ----
  const toast = document.getElementById("toast");
  let toastTimer = null;
  function showToast(msg) {
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove("show"), 2000);
  }

  document.querySelectorAll(".copy-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const text = btn.getAttribute("data-copy");
      if (!text) return;
      try {
        await navigator.clipboard.writeText(text);
        const orig = btn.textContent;
        btn.textContent = "Copied!";
        showToast("Copied command to clipboard");
        setTimeout(() => (btn.textContent = orig), 1500);
      } catch {
        showToast("Copy failed — select text manually");
      }
    });
  });

  // ---- GSAP Animations ----
  if (window.gsap) {
    if (window.ScrollTrigger) gsap.registerPlugin(ScrollTrigger);

    if (document.body.dataset.page === "home") {
      gsap.from(".hero-anim", {
        y: 28,
        opacity: 0,
        duration: 0.78,
        stagger: 0.1,
        ease: "power3.out",
      });

      document.querySelectorAll(".reveal").forEach((el) => {
        gsap.from(el, {
          y: 30,
          opacity: 0,
          duration: 0.7,
          ease: "power2.out",
          scrollTrigger: {
            trigger: el,
            start: "top 86%",
            once: true,
          },
        });
      });

      // Stat counters
      document.querySelectorAll(".stat-val[data-count]").forEach((el) => {
        const target = parseFloat(el.dataset.count);
        const decimals = parseInt(el.dataset.decimals || "0", 10);
        const prefix = el.dataset.prefix || "";
        const suffix = el.dataset.suffix || "";
        const obj = { val: 0 };
        gsap.to(obj, {
          val: target,
          duration: 1.35,
          ease: "power2.out",
          scrollTrigger: { trigger: el, start: "top 90%", once: true },
          onUpdate: () => {
            el.textContent = prefix + obj.val.toFixed(decimals) + suffix;
          },
        });
      });
    }
  }

  // ---- Interactive Terminal Simulator (Home) ----
  const termOut = document.getElementById("terminal-output");
  const termScripts = {
    init: [
      { cls: "t-cmd", text: "PS D:\\my-api> fast init --extra dev" },
      { cls: "t-dim", text: "Created virtual environment at D:\\my-api\\.venv (Python 3.12.7)" },
      { cls: "t-cyan", text: "Detected pyproject.toml (4 requirements, extras: [dev]) -> installing into .venv..." },
      { cls: "t-dim", text: "Resolved 14 packages in 28 ms" },
      { cls: "t-accent", text: "Installed 14 packages (412 files linked) in 210 ms" },
      { cls: "t-ok", text: "[OK] Completed in 334.02 ms" },
    ],
    install: [
      { cls: "t-cmd", text: "PS D:\\my-api> fast install requests -v" },
      { cls: "t-dim", text: "[diag] Detected Python 3.12.7 (win_amd64) in 480 us" },
      { cls: "t-dim", text: "Resolved 5 packages in 14 ms" },
      { cls: "t-dim", text: "[diag] Unpacked 5 wheels (5 cache hits) in 1 ms" },
      { cls: "t-accent", text: "Installed 5 packages (127 files linked) in 55 ms" },
      { cls: "t-ok", text: "[OK] Completed in 187.10 ms  (0.96x vs uv)" },
    ],
    update: [
      { cls: "t-cmd", text: "PS C:\\Users\\dev> fast self-update" },
      { cls: "t-dim", text: "Checking GitHub Releases (SarthakMitra323/Jolt-Package-Manager) -> latest is v1.0.0" },
      { cls: "t-cyan", text: "Downloading fast-windows-x64.exe (v1.0.0)..." },
      { cls: "t-dim", text: "Verified SHA-256: 9f4c8b...e21a04 (MATCH)" },
      { cls: "t-accent", text: "Updated %LOCALAPPDATA%\\jolt\\bin\\fast.exe to v1.0.0" },
      { cls: "t-ok", text: "[OK] Completed in 612.40 ms" },
    ],
  };

  let termTimers = [];
  function playTerminal(key) {
    if (!termOut) return;
    termTimers.forEach(clearTimeout);
    termTimers = [];
    termOut.innerHTML = "";
    const lines = termScripts[key] || termScripts.init;

    lines.forEach((line, idx) => {
      const t = setTimeout(() => {
        const div = document.createElement("div");
        div.className = line.cls;
        div.textContent = line.text;
        if (idx === lines.length - 1) {
          const cur = document.createElement("span");
          cur.className = "cursor";
          div.appendChild(cur);
        }
        termOut.appendChild(div);
      }, idx * 240);
      termTimers.push(t);
    });
  }

  document.querySelectorAll(".term-tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".term-tab").forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      playTerminal(tab.dataset.term);
    });
  });
  if (termOut) playTerminal("init");

  // ---- Interactive Benchmark Charts (Home) ----
  const benchRows = document.getElementById("bench-rows");
  const benchData = {
    warm: [
      { title: "Requests", meta: "5 packages · 127 files", ratio: "0.96× vs uv (Jolt wins)", cls: "ratio-win", bars: [
        { label: "jolt (fast.exe)", val: 187.1, kind: "jolt" },
        { label: "uv (Rust)", val: 194.6, kind: "uv" },
      ]},
      { title: "FastAPI + Uvicorn", meta: "14 packages · async web stack", ratio: "0.93× vs uv (Jolt wins)", cls: "ratio-win", bars: [
        { label: "jolt (fast.exe)", val: 334.0, kind: "jolt" },
        { label: "uv (Rust)", val: 357.7, kind: "uv" },
      ]},
      { title: "Django", meta: "4 packages · ~3,400 files", ratio: "1.16× vs uv", cls: "ratio-close", bars: [
        { label: "jolt (fast.exe)", val: 2189.1, kind: "jolt" },
        { label: "uv (Rust)", val: 1891.8, kind: "uv" },
      ]},
      { title: "NumPy + Pandas + SciPy", meta: "5 packages · heavy C-extensions", ratio: "1.00× vs uv (Dead heat)", cls: "ratio-tie", bars: [
        { label: "jolt (fast.exe)", val: 2693.7, kind: "jolt" },
        { label: "uv (Rust)", val: 2680.9, kind: "uv" },
      ]},
    ],
    p95: [
      { title: "FastAPI + Uvicorn (p95)", meta: "14 packages · tail latency", ratio: "1.82× tighter than uv", cls: "ratio-win", bars: [
        { label: "jolt (fast.exe)", val: 645.2, kind: "jolt" },
        { label: "uv (Rust)", val: 1174.0, kind: "uv" },
      ]},
      { title: "NumPy + Pandas + SciPy (p95)", meta: "5 packages · tail latency", ratio: "1.27× tighter than uv", cls: "ratio-win", bars: [
        { label: "jolt (fast.exe)", val: 3871.7, kind: "jolt" },
        { label: "uv (Rust)", val: 4922.7, kind: "uv" },
      ]},
      { title: "Django (p95)", meta: "4 packages · ~3,400 files", ratio: "1.19× vs uv", cls: "ratio-close", bars: [
        { label: "jolt (fast.exe)", val: 4563.9, kind: "jolt" },
        { label: "uv (Rust)", val: 3843.9, kind: "uv" },
      ]},
      { title: "Requests (p95)", meta: "5 packages · 127 files", ratio: "Warm p95", cls: "ratio-close", bars: [
        { label: "jolt (fast.exe)", val: 922.4, kind: "jolt" },
        { label: "uv (Rust)", val: 261.8, kind: "uv" },
      ]},
    ],
    audit: [
      { title: "NumPy + Pandas + SciPy (Audit)", meta: "Already installed verification", ratio: "30% faster than uv", cls: "ratio-win", bars: [
        { label: "jolt (fast.exe)", val: 174.7, kind: "jolt" },
        { label: "uv (Rust)", val: 250.9, kind: "uv" },
      ]},
      { title: "Django (Audit)", meta: "Already installed verification", ratio: "75.4 ms", cls: "ratio-close", bars: [
        { label: "jolt (fast.exe)", val: 75.4, kind: "jolt" },
        { label: "uv (Rust)", val: 56.1, kind: "uv" },
      ]},
      { title: "Requests (Audit)", meta: "Already installed verification", ratio: "100.1 ms", cls: "ratio-close", bars: [
        { label: "jolt (fast.exe)", val: 100.1, kind: "jolt" },
        { label: "uv (Rust)", val: 65.2, kind: "uv" },
      ]},
      { title: "FastAPI + Uvicorn (Audit)", meta: "Already installed verification", ratio: "183.2 ms", cls: "ratio-close", bars: [
        { label: "jolt (fast.exe)", val: 183.2, kind: "jolt" },
        { label: "uv (Rust)", val: 80.5, kind: "uv" },
      ]},
    ],
    pip: [
      { title: "Requests", meta: "Native fast.exe vs pip (warm)", ratio: "35.5× faster than pip", cls: "ratio-win", bars: [
        { label: "jolt (fast.exe)", val: 187.1, kind: "jolt" },
        { label: "pip (Python)", val: 6650.0, kind: "pip" },
      ]},
      { title: "FastAPI + Uvicorn", meta: "Native fast.exe vs pip (warm)", ratio: "52.2× faster than pip", cls: "ratio-win", bars: [
        { label: "jolt (fast.exe)", val: 334.0, kind: "jolt" },
        { label: "pip (Python)", val: 17443.0, kind: "pip" },
      ]},
      { title: "NumPy (AppCDS vs pip)", meta: "Heavy C-extension wheel", ratio: "13.5× faster than pip", cls: "ratio-win", bars: [
        { label: "jolt (JVM)", val: 1262.1, kind: "jolt" },
        { label: "pip (Python)", val: 17038.0, kind: "pip" },
      ]},
    ],
  };

  function renderBenchmarks(mode) {
    if (!benchRows) return;
    const list = benchData[mode] || benchData.warm;
    benchRows.innerHTML = "";

    list.forEach((item) => {
      const maxVal = Math.max(...item.bars.map((b) => b.val));
      const card = document.createElement("div");
      card.className = "bench-item";

      const barsHtml = item.bars
        .map((b) => {
          const pct = Math.max(4, (b.val / maxVal) * 100);
          return `
            <div class="bar-row">
              <div class="bar-label ${b.kind === "jolt" ? "is-jolt" : ""}">${b.label}</div>
              <div class="bar-track">
                <div class="bar-fill fill-${b.kind}" data-width="${pct.toFixed(1)}%"></div>
              </div>
              <div class="bar-value">${b.val.toLocaleString()} ms</div>
            </div>`;
        })
        .join("");

      card.innerHTML = `
        <div class="bench-item-head">
          <div>
            <div class="bench-title">${item.title}</div>
            <div class="bench-meta">${item.meta}</div>
          </div>
          <span class="bench-ratio ${item.cls}">${item.ratio}</span>
        </div>
        ${barsHtml}
      `;
      benchRows.appendChild(card);
    });

    requestAnimationFrame(() => {
      benchRows.querySelectorAll(".bar-fill").forEach((bar) => {
        bar.style.width = bar.getAttribute("data-width");
      });
    });
  }

  document.querySelectorAll(".bench-tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".bench-tab").forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      renderBenchmarks(tab.dataset.bench);
    });
  });
  if (benchRows) renderBenchmarks("warm");

  // ---- Docs Page: Search Filter & Scroll-Spy ----
  const docsSearch = document.getElementById("docs-search");
  const docsNavLinks = document.querySelectorAll("#docs-nav a");
  if (docsSearch) {
    docsSearch.addEventListener("input", () => {
      const q = docsSearch.value.trim().toLowerCase();
      docsNavLinks.forEach((link) => {
        const match = link.textContent.toLowerCase().includes(q);
        link.style.display = match ? "" : "none";
      });
    });
  }

  const docSections = document.querySelectorAll(".doc-section[id]");
  if (docSections.length && docsNavLinks.length) {
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const id = entry.target.id;
            docsNavLinks.forEach((a) => {
              a.classList.toggle("active", a.getAttribute("href") === `#${id}`);
            });
          }
        });
      },
      { rootMargin: "-20% 0px -65% 0px" }
    );
    docSections.forEach((sec) => observer.observe(sec));
  }
})();