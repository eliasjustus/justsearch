from pathlib import Path
text = Path('main/java/io/justsearch/ui/viewmodel/MainViewModel.java').read_text()
lines = text.splitlines()
for i in range(400, 460):
    print(f"{i+1:04d}: {lines[i]}")
