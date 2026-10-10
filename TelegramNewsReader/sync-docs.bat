@echo off
for %%f in (README.md README_EN.md PRIVACY.md PRIVACY_EN.md) do copy /Y "%%f" "..\%%f"