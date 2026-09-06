"""Tkinter presentation for blinded duplicate-pair labeling."""

from __future__ import annotations

import tkinter as tk
from tkinter import messagebox, ttk
from tkinter.scrolledtext import ScrolledText

from .duplicate_review_labels import DuplicateReviewSession


_CHOICES = (
    ("NEAR_DUPLICATE", "1 — Near duplicate"),
    ("NOT_NEAR_DUPLICATE", "2 — Not near duplicate"),
    ("UNCERTAIN", "3 — Uncertain"),
    ("ABSTAIN", "4 — Abstain"),
)
INSTRUCTIONS = (
    "Judge substantive content only; ignore wrappers, quoting, headers, and formatting.\n"
    "Near duplicate = the same substantive content despite wrappers, quoting, or formatting.\n"
    "Not near duplicate = substantively distinct content.\n"
    "Uncertain = both texts are reviewable but the semantic judgment is ambiguous.\n"
    "Abstain = no judgment is possible because a text is unreadable, truncated, or otherwise not reviewable."
)


def launch_label_gui(session: DuplicateReviewSession) -> None:
    """Open the human-facing review window and block until it is closed."""

    root = tk.Tk()
    root.title("Duplicate pair review")
    root.geometry("1180x760")
    root.minsize(840, 560)

    outer = ttk.Frame(root, padding=12)
    outer.pack(fill=tk.BOTH, expand=True)
    header = ttk.Label(
        outer,
        text=(
            "Only pairs the blinded model triage could not confidently resolve are shown.\n"
            + INSTRUCTIONS
            if session.triaged
            else INSTRUCTIONS
        ),
        justify=tk.LEFT,
        wraplength=1120,
    )
    header.pack(fill=tk.X, pady=(0, 8))

    status_var = tk.StringVar()
    saved_var = tk.StringVar()
    status_row = ttk.Frame(outer)
    status_row.pack(fill=tk.X, pady=(0, 8))
    ttk.Label(status_row, textvariable=status_var).pack(side=tk.LEFT)
    ttk.Label(status_row, textvariable=saved_var).pack(side=tk.RIGHT)

    panes = ttk.Panedwindow(outer, orient=tk.HORIZONTAL)
    panes.pack(fill=tk.BOTH, expand=True)
    text_widgets: list[ScrolledText] = []
    for title in ("Text A", "Text B"):
        frame = ttk.Labelframe(panes, text=title, padding=6)
        widget = ScrolledText(frame, wrap=tk.WORD, font=("TkFixedFont", 10), undo=False)
        widget.pack(fill=tk.BOTH, expand=True)
        widget.configure(state=tk.DISABLED)
        panes.add(frame, weight=1)
        text_widgets.append(widget)

    controls = ttk.Frame(outer)
    controls.pack(fill=tk.X, pady=(10, 0))

    def render() -> None:
        first, second = session.current_texts()
        for widget, value in zip(text_widgets, (first, second)):
            widget.configure(state=tk.NORMAL)
            widget.delete("1.0", tk.END)
            widget.insert("1.0", value)
            widget.configure(state=tk.DISABLED)
            widget.yview_moveto(0.0)
        status_var.set(
            f"Pair {session.index + 1} of {session.count}  •  "
            f"{session.completed_count} saved"
        )
        current = session.current_label
        saved_var.set(f"Current saved label: {current or 'none'}")

    def choose(label: str) -> None:
        try:
            session.label_current(label)
        except Exception as exc:  # UI boundary: preserve current pair on every save failure.
            messagebox.showerror("Label was not saved", str(exc), parent=root)
            return
        render()

    def move(offset: int) -> None:
        session.move(offset)
        render()

    ttk.Button(controls, text="← Back", command=lambda: move(-1)).pack(side=tk.LEFT)
    ttk.Button(controls, text="Skip / Next →", command=lambda: move(1)).pack(side=tk.LEFT, padx=(6, 18))
    for label, caption in _CHOICES:
        ttk.Button(controls, text=caption, command=lambda value=label: choose(value)).pack(
            side=tk.LEFT, padx=3
        )

    for key, (label, _caption) in zip(("1", "2", "3", "4"), _CHOICES):
        root.bind(key, lambda _event, value=label: choose(value))
    root.bind("<Left>", lambda _event: move(-1))
    root.bind("<Right>", lambda _event: move(1))

    render()
    root.mainloop()


__all__ = ["INSTRUCTIONS", "launch_label_gui"]
