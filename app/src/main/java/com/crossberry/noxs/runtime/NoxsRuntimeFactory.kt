/*
 * Noxs — original implementation.
 * Factory for the runtime layer, owned by the foreground service lifecycle.
 */
package com.crossberry.noxs.runtime

object NoxsRuntimeFactory {

    fun launcher(paths: NoxsPaths, resources: NoxsResources): ProotLauncher =
        ProotLauncher(paths, resources)

    fun executor(launcher: ProotLauncher): OneShotExecutor = OneShotExecutor(launcher)

    fun packages(exec: OneShotExecutor): PackageManagerControl = PackageManagerControl(exec)

    fun users(exec: OneShotExecutor, paths: NoxsPaths, launcher: ProotLauncher): UserManagerControl =
        UserManagerControl(exec, paths, launcher)

    fun services(exec: OneShotExecutor, paths: NoxsPaths): ServiceManagerControl =
        ServiceManagerControl(exec, paths)

    fun processes(exec: OneShotExecutor): ProcessManagerControl = ProcessManagerControl(exec)

    fun env(paths: NoxsPaths): EnvManager = EnvManager(paths)

    fun storage(paths: NoxsPaths, resources: NoxsResources): StorageManager =
        StorageManager(paths, resources)
}
