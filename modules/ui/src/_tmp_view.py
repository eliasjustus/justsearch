from pathlib import Path
text = Path('main/java/io/justsearch/ui/view/MainView.java').read_text()
lines = text.splitlines()
for i in range(400, 520):
    print(f"{i+1:04d}: {lines[i]}")
