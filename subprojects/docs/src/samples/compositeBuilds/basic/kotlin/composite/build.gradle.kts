plugins {
    idea
}

defaultTasks("run")

// tag::run[]
task("run") {
    dependsOn(gradle.includedBuild("my-app").task(":run"))
}
// end::run[]

task("checkAll") {
    dependsOn(gradle.includedBuild("my-app").task(":check"))
    dependsOn(gradle.includedBuild("my-utils").task(":number-utils:check"))
    dependsOn(gradle.includedBuild("my-utils").task(":string-utils:check"))
}
