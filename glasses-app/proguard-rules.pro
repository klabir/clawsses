# App-specific R8 rules belong here. The current HUD uses standard Android/Compose code and the
# vendor bridge is supplied as ordinary bytecode, so the optimized default configuration is enough.
# Keeping this file explicit prevents release builds from silently ignoring their configured rules.
