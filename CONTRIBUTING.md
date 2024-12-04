# Contributing

## Development Flow

1. Fork it
2. Create your feature branch (`git checkout -b my-new-feature`)
3. Commit your changes (`git commit -am 'Add some feature'`)
4. Ensure you have added suitable tests and the test suite is passing(`./gradlew check`)
5. Push to the branch (`git push origin my-new-feature`)
6. Create a new Pull Request

### Building

A gradle wrapper is included, so these tasks can run without any prior installation of gradle. The Linux/OSX form of the commands, given
below, is:

```bash
./gradlew <task name>
```

For Windows, use the batch file:

```cmd
gradlew.bat <task name>
```

To build the library’s AAR package, execute:

```bash
./gradlew chat-android:assemble
```

### Code Standard

#### Detekt

We use [Detekt](https://detekt.dev/) for code style checks and static analysis in this project.
Detekt helps maintain a consistent codebase and identify potential issues in the code.
Before submitting a pull request, ensure that your code passes all Detekt checks.

#### Running Detekt Locally

You can run Detekt locally to verify your code against the configured rules. Use the following command:

```bash
./gradlew detekt
```

#### Updating the rules

Detekt’s rules can be updated by modifying the [detekt.yml](https://github.com/ably/ably-chat-kotlin/blob/main/detekt.yml) configuration
file. Ensure that any changes to the rules are well-documented and align with the project’s coding standards.

### Running Tests

Our tests are written using JUnit and can be executed with the following command:

```bash
./gradlew test
```

This will run all unit tests in the project. Ensure all tests pass before submitting a pull request.

## Using `chat-android` locally in other projects

If you wish to make changes to the source code and test them immediately in another project, follow these steps:

### Step 1: Update the Version Property

Modify the version property in `gradle.properties` to reflect a local version:

```properties
VERSION_NAME=0.1.0-local.1
```

### Step 2: Publish to Maven Local

Publish the library to your local Maven repository by running:

```bash
./gradlew publishToMavenLocal
```

### Step 3: Use the Local Version in Another Project

In the project where you want to use the updated library:

1. Add the mavenLocal() repository to your repositories block:
   ```kotlin
   repositories {
       mavenLocal()
       // other repositories...
   }
   ```

2. Add a dependency for the local version of the library:
    ```kotlin
    implementation("com.ably.chat:chat-android:0.1.0-local.1")
    ```

> [!NOTE]
> - Ensure the version number (`0.1.0-local.1`) matches the one you set in gradle.properties.
> - The `mavenLocal()` repository should typically be used only during development to avoid conflicts with published versions in remote
    repositories.

## Release Process

### Prerequisites for Release

Before starting the release process, ensure you have:

1. Sonatype OSSRH account credentials configured in your `~/.gradle/gradle.properties`:
   ```properties
   mavenCentralUsername=user-token-username
   mavenCentralPassword=user-token-password
   ```
2. GPG key for signing artifacts:

- Generate a key pair if you don't have one: `gpg --gen-key`
- Export the secret key to gradle.properties:
    ```properties
    signing.keyId=short-key-id
    signing.password=key-password
    signing.secretKeyRingFile=/path/to/.gnupg/secring.gpg
    ```

## Release Process

This library uses [semantic versioning](http://semver.org/). For each release, the following needs to be done:

1. Create a branch for the release, named like `release/1.2.4` (where `1.2.4` is what you're releasing, being the new version)
2. Replace all references of the current version number with the new version number (check the [README.md](./README.md)
   and [gradle.properties](./gradle.properties)) and commit the changes
3. Run [`github_changelog_generator`](https://github.com/github-changelog-generator/github-changelog-generator) to automate the update of
   the [CHANGELOG](./CHANGELOG.md). This may require some manual intervention, both in terms of how the command is run and how the change
   log file is modified. Your mileage may vary:

    - The command you will need to run will look something like this:
      `github_changelog_generator -u ably -p ably-chat-kotlin --since-tag v1.2.3 --output delta.md --token $GITHUB_TOKEN_WITH_REPO_ACCESS`.
      Generate token [here](https://github.com/settings/tokens/new?description=GitHub%20Changelog%20Generator%20token).
    - Using the command above, `--output delta.md` writes changes made after `--since-tag` to a new file.
    - The contents of that new file (`delta.md`) then need to be manually inserted at the top of the `CHANGELOG.md`, changing the "
      Unreleased"
      heading and linking with the current version numbers.
    - Also ensure that the "Full Changelog" link points to the new version tag instead of the `HEAD`.

4. Commit [CHANGELOG](./CHANGELOG.md)
5. Make a PR against `main`
6. Once the PR is approved, merge it into `main`
7. From the updated `main` branch on your local workstation, assemble and upload:
    ```sh
      ./gradlew publishAndReleaseToMavenCentral
    ```
    - Verify the upload was successful by checking the Maven Central repository
    - If the upload fails, check the Sonatype staging repository for any validation errors
    - Common issues include:
        - Missing POM file information
        - Invalid signatures
        - Incomplete Javadoc
8. Add a tag and push to origin - e.g.: `git tag v1.2.4 && git push origin v1.2.4`
9. Create the release on Github including populating the release notes
10. Create the entry on the [Ably Changelog](https://changelog.ably.com/) (via [headwayapp](https://headwayapp.co/))
