package me.virul.nexora.loader;

public enum PluginLifecycle {
    UNLOADED,
    LOADING,
    LOADED,
    INITIALIZING,
    ACTIVE,
    DEACTIVATING,
    INACTIVE,
    FAILED
}
