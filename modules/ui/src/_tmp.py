from pathlib import Path
text = Path('main/java/io/justsearch/ui/viewmodel/MainViewModel.java').read_text()
lines = text.splitlines()
for i in range(315, 400):
    print(f"{i+1:04d}: {lines[i]}")
