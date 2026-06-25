from pathlib import Path
text = Path('main/java/io/justsearch/ui/view/MainView.java').read_text()
lines = text.splitlines()
for i in range(560, 640):
    print(f"{i+1:04d}: {lines[i]}")
