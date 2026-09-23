# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Maintenance
- Add Backstage catalog-info.yaml (#172) ([0b8f586](https://github.com/galax-io/gatling-jdbc-plugin/commit/0b8f58625a6bef200d533f6fe00c80ecccb44909))

### Other
- Update scalafmt-core to 3.11.5 (#164) ([1d3c4eb](https://github.com/galax-io/gatling-jdbc-plugin/commit/1d3c4ebcb396425a639978283bbb2cb073d03693))
- Update sbt, scripted-plugin to 1.12.15 (#165) ([cbf40b1](https://github.com/galax-io/gatling-jdbc-plugin/commit/cbf40b1c83270b28f03e47c87dd1e90b3d92d89d))
- Update gatling-sbt to 4.19.1 (#163) ([a2c40f0](https://github.com/galax-io/gatling-jdbc-plugin/commit/a2c40f0fe674ae357401970c6c0a0a5bafe938c1))
## [1.5.0] - 2026-07-23

### Fixes
- Unresolvable javadoc {@link} broke scaladoc generation (v1.5.0 release) (#162) ([01abbf7](https://github.com/galax-io/gatling-jdbc-plugin/commit/01abbf728c72151a83f176adf65aca501d5ef30e))
- Store literal "NULL" strings as text, not SQL NULL (#93) ([e018aa7](https://github.com/galax-io/gatling-jdbc-plugin/commit/e018aa767a3a253ba62e31d74b3d65f062566a15))
- Warn on secret-like datasource properties and URL creds (#92) ([4189767](https://github.com/galax-io/gatling-jdbc-plugin/commit/41897673204e0b5423d8476bea12575eb117692e))
- Report structured value-free KO messages (#126) ([e6ac651](https://github.com/galax-io/gatling-jdbc-plugin/commit/e6ac651bc6b1fc0335b681693a5abefef125b592))
- Redact credentials in builder toString and URLs (#91) ([4383914](https://github.com/galax-io/gatling-jdbc-plugin/commit/43839149e57764988b14eece92b7a13fe2f7cf26))
- Parameterize batch WHERE values, reject EL predicates (#125) ([4b3563b](https://github.com/galax-io/gatling-jdbc-plugin/commit/4b3563bf09ceeb54e3d6858f97ffc50c203f742a))
- Validate procedure identifiers before CALL assembly (#90) ([f61db72](https://github.com/galax-io/gatling-jdbc-plugin/commit/f61db7217b385f508236fbc96e5d6487c739b342))

### Documentation
- V1.5.0 migration notes; mark 005 tasks complete ([c6b6d49](https://github.com/galax-io/gatling-jdbc-plugin/commit/c6b6d498d110aa19f5ad6deeaade27b51cf7e2ee))
- Add 005-injection-secrets-null spec/plan/tasks ([d96c0d4](https://github.com/galax-io/gatling-jdbc-plugin/commit/d96c0d416264e99ec9ffcc591e22072087edc1bb))
- Amend constitution to v1.1.0 (Principle I corruption & injection carve-out) ([2e4396f](https://github.com/galax-io/gatling-jdbc-plugin/commit/2e4396f3198a780137e06eb802f8b4486131e817))
## [1.4.0] - 2026-07-21

### Features
- Bounded result retrieval - discard path and maxRows cap (#86) ([eb0ec56](https://github.com/galax-io/gatling-jdbc-plugin/commit/eb0ec561c5d4e76cc2c7d557350bbe923a22f9f4))

### Fixes
- A PR closing no issue is not itself a linkage violation ([2284c2c](https://github.com/galax-io/gatling-jdbc-plugin/commit/2284c2ca1ef5588531a51c46526d4786dae447d6))
- Validate SQL identifiers before statement assembly (#124) ([735ae36](https://github.com/galax-io/gatling-jdbc-plugin/commit/735ae36e8c7d27a84619c38944f99b3064703d69))
- Detach LOB values while the ResultSet is open (#87) ([be5be5b](https://github.com/galax-io/gatling-jdbc-plugin/commit/be5be5be569ef0cb3b511b2bfd2d8a92e604b471))
- Preserve primary batch failure over cleanup failures (#84) ([a33669d](https://github.com/galax-io/gatling-jdbc-plugin/commit/a33669d5207325b0eaf0e80ea06162979adbffcb))
- Fail loud on duplicate ResultSet labels (#123) ([f358a61](https://github.com/galax-io/gatling-jdbc-plugin/commit/f358a61c496f3d3a766f143615de4debb41d825e))
- Key result rows by column label, not physical name (#122) ([896484a](https://github.com/galax-io/gatling-jdbc-plugin/commit/896484ab620c7c83c7ea69ee922280c591b037f7))
- Reject autoCommit=false pool configuration at startup (#88) ([608ef81](https://github.com/galax-io/gatling-jdbc-plugin/commit/608ef81685180aeabb9829d6f6b9dcfe42054a1e))
- Release gate cannot resolve patch-version milestones (#157) ([689ee0a](https://github.com/galax-io/gatling-jdbc-plugin/commit/689ee0a6bdb5288db62c8d26440836812221e8fe))

### Documentation
- Mark 003-batch-resultset-correctness tasks complete ([91926aa](https://github.com/galax-io/gatling-jdbc-plugin/commit/91926aaa76879492f95dd8d5165e18e9602b4b04))
- Add 003-batch-resultset-correctness plan, design artifacts, and tasks ([469c841](https://github.com/galax-io/gatling-jdbc-plugin/commit/469c8415d900d209674ea81f6ebfaa002ead91ac))
- Add 003-batch-resultset-correctness spec ([486cacc](https://github.com/galax-io/gatling-jdbc-plugin/commit/486caccaafb17189861b96d04dc6234375e86834))
- Mark 004-review-followups tasks complete with as-built notes ([5d79b92](https://github.com/galax-io/gatling-jdbc-plugin/commit/5d79b9217f2dd87551029433cab2c70c1ad41cac))
- Document batch execution ordering and adjacent-grouping rule (#155) ([a44bc1e](https://github.com/galax-io/gatling-jdbc-plugin/commit/a44bc1ea531262363d1ddefe5f5f08df618799ed))
- Warn that Java QueryActionBuilder.check returns a new builder (#152) ([9fb7556](https://github.com/galax-io/gatling-jdbc-plugin/commit/9fb7556bbf1784be800469a796f2e2da0b2b5f85))
- Add 004-review-followups spec, plan, and design artifacts ([3cd4d2f](https://github.com/galax-io/gatling-jdbc-plugin/commit/3cd4d2f2504b9e8ecd6ac0f5e01698a3b5a992a3))

### Maintenance
- Share H2 client fixture across runtime-correctness specs ([3ebf30c](https://github.com/galax-io/gatling-jdbc-plugin/commit/3ebf30cb2f80af0cdb55b2002a5134e26beecaec))
- Make check-chain regression test detect replacement standalone (#154) ([5987737](https://github.com/galax-io/gatling-jdbc-plugin/commit/59877371e9ab5e05138952fc67e824053bb57acf))
- Consolidate duplicated check-failure KO path (#153) ([c8c3e6f](https://github.com/galax-io/gatling-jdbc-plugin/commit/c8c3e6f1f875a65e54b3d7de5bbb60b1a293bf19))
- Restore scratchpad pattern and ignore .worktrees/ on separate lines ([55b3eeb](https://github.com/galax-io/gatling-jdbc-plugin/commit/55b3eebdb5f173a6a5faa8542616eac5209ef125))

### Other
- Update scalafmt-core to 3.11.4 ([7ba8aa1](https://github.com/galax-io/gatling-jdbc-plugin/commit/7ba8aa19250db199764743ac9cf17d578930c6bc))
- Update sbt-scalafmt to 2.6.2 ([d5f320e](https://github.com/galax-io/gatling-jdbc-plugin/commit/d5f320ea40041a37b11b2779df5fe020c271445d))
- Update HikariCP to 7.1.0 ([392124e](https://github.com/galax-io/gatling-jdbc-plugin/commit/392124e405a009f39309035c3f4140649559e481))
## [1.3.0] - 2026-07-19

### Fixes
- Preserve declared SQL order in batch execution (#82) ([3590c2a](https://github.com/galax-io/gatling-jdbc-plugin/commit/3590c2a7da783861ac070cac7adff4c9a91023e3))
- Stop QueryActionBuilder.check mutating shared builder branches (#80) ([bbd1973](https://github.com/galax-io/gatling-jdbc-plugin/commit/bbd1973db0e612211ea9647e5fe7945ff47ae7f4))
- Append chained query checks instead of replacing them (#79) ([c40aa6e](https://github.com/galax-io/gatling-jdbc-plugin/commit/c40aa6e1385e613bb953053c295624fe587eb271))
- Report KO instead of hanging VU when a JDBC check throws (#78) ([f5b26eb](https://github.com/galax-io/gatling-jdbc-plugin/commit/f5b26ebf26495701263783ba12625d81923d6ca0))
- Emit KO instead of hanging VU on unresolved requestName EL (#77) ([4e4d600](https://github.com/galax-io/gatling-jdbc-plugin/commit/4e4d6001475c81bbb61cfd3f76991a7d9d680ee0))
- Stop dropping non-Conventional-Commit messages from changelog (#145) ([f2727a8](https://github.com/galax-io/gatling-jdbc-plugin/commit/f2727a8467beab7f2c9a241d34bf1dfca732468f))

### Documentation
- Mark 002-check-semantics-concurrency tasks complete with as-built notes ([b25f239](https://github.com/galax-io/gatling-jdbc-plugin/commit/b25f239bc4beaa80b7846d49b5337997eb520894))
- Add 002-check-semantics-concurrency spec, plan, and tasks ([e9dd28d](https://github.com/galax-io/gatling-jdbc-plugin/commit/e9dd28d1e6d42247996882f0780c82df3e2a440a))

### Maintenance
- Extract shared fixtures and unify H2 pool config across specs ([f0292e6](https://github.com/galax-io/gatling-jdbc-plugin/commit/f0292e6c90253a6f68f97d4da18646af4627cf88))
- Close spectest gaps — exactly-once KO on DB errors, statement reuse per batch run ([39755aa](https://github.com/galax-io/gatling-jdbc-plugin/commit/39755aaa9bb5f4d5c7b4cec835c01528f07fcfb9))
## [1.2.0] - 2026-07-12

### Documentation
- Mark 001-concurrency-hardening tasks complete with as-built notes (PR #140) ([3d07b44](https://github.com/galax-io/gatling-jdbc-plugin/commit/3d07b448dfb5d18a4593a9a96df03fe0f6195814))
- Add 001-concurrency-hardening spec/plan/tasks ([91a0151](https://github.com/galax-io/gatling-jdbc-plugin/commit/91a0151ae0414fd25bc199a17fffb0b84246d31e))
- Ratify constitution v1.0.0 (5 principles + governance baseline) (#139) ([75925b6](https://github.com/galax-io/gatling-jdbc-plugin/commit/75925b67046695501746f41174fa7daa24244b20))

### Maintenance
- Prove exactly-once resource release on synchronous failure (#100) ([c5acf7a](https://github.com/galax-io/gatling-jdbc-plugin/commit/c5acf7a1b93d95c44631febf57cebc0479f83a6e))
- Prove batch honors configured queryTimeout (#83) ([13616d4](https://github.com/galax-io/gatling-jdbc-plugin/commit/13616d42fd8ba46d69a25332d727bdfee2c9dafd))
- Prove serialized CallableStatement IN/OUT registration (#121) ([99c6333](https://github.com/galax-io/gatling-jdbc-plugin/commit/99c6333a4cec3211fb4f6f1088e2e238dc1f6175))
- Prove serialized PreparedStatement param binding (#120) ([43d248d](https://github.com/galax-io/gatling-jdbc-plugin/commit/43d248d7274786ed6cc5e6f7418e3612f20daac7))
- Enforce current-milestone assignment via milestone-guard hook (#138) ([5c2c9c5](https://github.com/galax-io/gatling-jdbc-plugin/commit/5c2c9c5ac2d4d7fc891ed5c213050c5079a7a048))

### Other
- Fix Deadlock on Backpressure From Connection Pool (#59) ([38b99bd](https://github.com/galax-io/gatling-jdbc-plugin/commit/38b99bd02a8920706b126635e5d4a6f1c3e3af73))
## [1.1.0] - 2026-07-12

### Documentation
- Adopt galax-io AGENTS/CLAUDE guide ([d82502b](https://github.com/galax-io/gatling-jdbc-plugin/commit/d82502bf40474c6dcc7264d657b5c69320dea387))

### Dependencies
- Update sbt-ci-release to 1.12.0 (#134) ([d0cd2b6](https://github.com/galax-io/gatling-jdbc-plugin/commit/d0cd2b6ee373d35e7b6c5d9a3d9e6d9a228fc7fa))

### Maintenance
- Generate release notes with git-cliff; split ci/release ([3abae2b](https://github.com/galax-io/gatling-jdbc-plugin/commit/3abae2b5277fddbea38786087d90252c4f721685))
- Add scalafmt pre-commit git hook ([695df5d](https://github.com/galax-io/gatling-jdbc-plugin/commit/695df5dd2999fd74dca3b8e99e77da79815373a7))
- Read git via console so sbt loads in worktrees ([df6f05d](https://github.com/galax-io/gatling-jdbc-plugin/commit/df6f05df6d5f58e5e00eacaaef70230f2a578fdc))
- Install spec-kit toolkit ([0a3b293](https://github.com/galax-io/gatling-jdbc-plugin/commit/0a3b2936320b3ed519a0ee43d6c3621cddbb5a16))
- Add issue/PR/milestone linkage guard ([172ca79](https://github.com/galax-io/gatling-jdbc-plugin/commit/172ca7983eb87438e594e50e2358e843dedceb3d))

### Other
- Update postgresql to 1.21.4 ([72e8a15](https://github.com/galax-io/gatling-jdbc-plugin/commit/72e8a15a3835768d57b9a20b2b980f9e1fdc38f8))
- Update scalafmt-core to 3.10.7 ([0474cb3](https://github.com/galax-io/gatling-jdbc-plugin/commit/0474cb3f46703e4ea8a3124db4daa0ceb538d027))
- Update HikariCP to 6.3.3 ([48930e6](https://github.com/galax-io/gatling-jdbc-plugin/commit/48930e67bd36110365058c8d4df462bddd56bd6a))
- Update postgresql to 42.7.13 ([054f52d](https://github.com/galax-io/gatling-jdbc-plugin/commit/054f52d9d1ff97ff9c1dc1f2eae24f409a05298a))
- Update gatling-sbt to 4.18.4 ([a1ff411](https://github.com/galax-io/gatling-jdbc-plugin/commit/a1ff411c3cd3f161f8259ebff0466b640468816b))
## [1.0.4] - 2026-07-10

### Maintenance
- Gitignore Metals/Bloop artifacts and generated .mcp.json ([e55fff5](https://github.com/galax-io/gatling-jdbc-plugin/commit/e55fff5cbc81c80bae9725a21318945ee04fdfe9))
## [1.0.3] - 2026-05-31

### Documentation
- Update compatibility table for 1.0.0 (Java 11+) ([a8d0401](https://github.com/galax-io/gatling-jdbc-plugin/commit/a8d0401bd92ea694f5f550dd279e61d5581408c3))
## [1.0.1] - 2026-05-31

### Documentation
- Fix README to match actual API surface ([d585862](https://github.com/galax-io/gatling-jdbc-plugin/commit/d5858621db2361ddd8ed800cc90cd130aadbaecf))
## [1.0.2] - 2026-05-31

### Breaking changes
- Extract shared error handling into ActionBase ([c84146b](https://github.com/galax-io/gatling-jdbc-plugin/commit/c84146b0e668aa2ecb9ae7108793649014038e9c))

### Maintenance
- Narrow statement wrapper traits, remove dead batch methods ([ac7377b](https://github.com/galax-io/gatling-jdbc-plugin/commit/ac7377b2f7e1ec0a192df615434de730d2a3fd8b))
- Remove dead substituteParams/paramValueToSql ([b4aa4a9](https://github.com/galax-io/gatling-jdbc-plugin/commit/b4aa4a90092a702da812433fb678d5d09170efef))
## [0.20.6] - 2026-05-31

### Dependencies
- Update sbt and sbt-scalafmt ([bc05965](https://github.com/galax-io/gatling-jdbc-plugin/commit/bc059652d736b9b7844a23ebf1c71367a0b8765c))
- Update dependencies to latest stable ([7397650](https://github.com/galax-io/gatling-jdbc-plugin/commit/7397650ae739d3a1993f9b6cd36c815d3485c26c))
## [0.20.5] - 2026-05-31

### Fixes
- Bounded thread pool and proper JDBC resource cleanup (#46) ([e5d1a35](https://github.com/galax-io/gatling-jdbc-plugin/commit/e5d1a35e769da20b92af8b89cfeb1326ceb7a5ad))

### Maintenance
- Track AGENTS.md and CLAUDE.md, remove from .gitignore ([e1b812e](https://github.com/galax-io/gatling-jdbc-plugin/commit/e1b812e5cc77378df6bc6e9b8c6a266957cebfdb))
## [0.20.4] - 2026-05-31

### Documentation
- Expand AGENTS.md with full project agent instructions ([15be2e2](https://github.com/galax-io/gatling-jdbc-plugin/commit/15be2e2f038eb36f1c50301349207801c0933fbd))
## [0.20.3] - 2026-05-31

### Documentation
- Add Database Driver Dependencies section ([0ea52c9](https://github.com/galax-io/gatling-jdbc-plugin/commit/0ea52c9174db5ee23ae3fb322f4952c375c4c4ea))
- Replace non-existent DockerTest command with real test workflow ([5f0532c](https://github.com/galax-io/gatling-jdbc-plugin/commit/5f0532ce260667e6da4773d792e57d3b7d98c8d9))
- Fix Java and Kotlin imports to use real javaapi package path ([69b2a5c](https://github.com/galax-io/gatling-jdbc-plugin/commit/69b2a5cdd96445e4a6e999fc480157e0d4697233))
- Fix compatibility matrix to reflect Gatling 3.13.x on main ([f20503b](https://github.com/galax-io/gatling-jdbc-plugin/commit/f20503baa79227fa6ba43beb140930891e130425))
## [0.20.2] - 2026-05-31

### Fixes
- Replace unresolvable Scaladoc links in JdbcProtocolBuilder (#44) ([dd7de89](https://github.com/galax-io/gatling-jdbc-plugin/commit/dd7de89deeb93b713bacd6b761ae31f90275fe24))
## [0.20.1] - 2026-05-31

### Maintenance
- Add Testcontainers PostgreSQL integration suite for JDBCClient (#43) ([38e0d15](https://github.com/galax-io/gatling-jdbc-plugin/commit/38e0d15673d29fae050661113f48929afcb9d66f))
## [0.20.0] - 2026-05-31

### Features
- Implement OUT parameter extraction for stored procedures (Scala + Java API) (#42) ([0a5d8f6](https://github.com/galax-io/gatling-jdbc-plugin/commit/0a5d8f61c1a01bf08aaeaebede4b86ca1e0676af))
- Add unit regression tests for ResourceFut, SQL mapping, and action failure (#41) ([fa87df9](https://github.com/galax-io/gatling-jdbc-plugin/commit/fa87df9dc20a30a430581f4c4acf17245ff3ab58))
## [0.19.6] - 2026-05-30

### Fixes
- Round up sub-second queryTimeout to 1s, document semantics, fix EventLoopGroup leak in tests (#40) ([b4ebb71](https://github.com/galax-io/gatling-jdbc-plugin/commit/b4ebb71056179df8ec866b9878269cb104153094))
## [0.19.5] - 2026-05-30

### Fixes
- Use prepared statements in batch execution to prevent SQL injection (#38) ([b1b8ad2](https://github.com/galax-io/gatling-jdbc-plugin/commit/b1b8ad2483b2c147a14b2e11ee9a04991fe287e0))
## [0.19.4] - 2026-05-30

### Fixes
- Mark Gatling session as failed when JDBC execution returns KO (#35) ([61ea472](https://github.com/galax-io/gatling-jdbc-plugin/commit/61ea472153813b6505386e5c58496d82138cf2e6))
- Preserve original JDBC exception when resource cleanup also fails (#36) ([d0a33ae](https://github.com/galax-io/gatling-jdbc-plugin/commit/d0a33ae36f289b102b4574071be615fcc36b3616))
## [0.19.3] - 2026-05-30

### Fixes
- Implement queryTimeout and align README with JdbcProtocolBuilder API (#37) ([549b689](https://github.com/galax-io/gatling-jdbc-plugin/commit/549b68992a52851dd45ec69bc975280fa23e9875))
- Handle null values in withParamsMap to prevent NPE ([d3be6dc](https://github.com/galax-io/gatling-jdbc-plugin/commit/d3be6dc4df99791f67e767307c75f836ab23b9f0))
- Preserve typed Java/Kotlin parameter values instead of coercing to strings ([0eb7240](https://github.com/galax-io/gatling-jdbc-plugin/commit/0eb724065470fbc177592636ab5548bd418b215c))

### Maintenance
- Apply scalafmt formatting ([7399d58](https://github.com/galax-io/gatling-jdbc-plugin/commit/7399d5808374ba928dc127601c56ec30515d70ca))
## [0.19.2] - 2026-05-30

### Maintenance
- Update .gitignore ([321bc0c](https://github.com/galax-io/gatling-jdbc-plugin/commit/321bc0c1b339ef04b9ba9dc7ed44d2d4b723fedc))
## [0.19.1] - 2026-05-25

### Fixes
- Close JDBC resources at simulation shutdown, not per-user exit ([8350384](https://github.com/galax-io/gatling-jdbc-plugin/commit/835038402e50ae9def2b1b6159655115e976b334))
- Replace unbounded thread pool with bounded fixed pool ([c9a2e0b](https://github.com/galax-io/gatling-jdbc-plugin/commit/c9a2e0bd42906574f0dab00f255d1df8a9775f81))
## [0.19.0] - 2026-05-19

### Features
- Align main with gatling 3.13 ([9c6ae89](https://github.com/galax-io/gatling-jdbc-plugin/commit/9c6ae89efdbaf2e98d7afbf2f692e4d9abb0074c))
- Merge latest/gatling into main ([6447ab4](https://github.com/galax-io/gatling-jdbc-plugin/commit/6447ab4db27fe667152159be51f5b1c86255c6c3))
## [0.17.1-latest] - 2026-05-17

### Documentation
- Fix compatibility table — suffix-based versioning ([d930741](https://github.com/galax-io/gatling-jdbc-plugin/commit/d9307417d107df2052158b90fc1203725197b074))
- Unified README with compatibility table, ToC, and 3-language quick start ([0bba942](https://github.com/galax-io/gatling-jdbc-plugin/commit/0bba942b33ed8e33beabb3b215314d8252e0d8ff))

### Maintenance
- Merge main into latest/gatling ([b2c01e7](https://github.com/galax-io/gatling-jdbc-plugin/commit/b2c01e7f772e6aa32863ac179c788b4747e704b0))
## [0.13.0-latest] - 2024-12-15

### Maintenance
- Upgrade Gatling 3.13.1 ([f14c848](https://github.com/galax-io/gatling-jdbc-plugin/commit/f14c848cadb1496a40727586f55d9746c77abcf1))
- Upgrade HikariCP 6.2.1 ([fc4ef81](https://github.com/galax-io/gatling-jdbc-plugin/commit/fc4ef815caba9bf74a79b121c0c9b5989337596b))
- Upgrade h2 2.3.232 ([63a8f20](https://github.com/galax-io/gatling-jdbc-plugin/commit/63a8f202a20307aced7d8af462a8650c14b7d1bb))
- Upgrade sbt 1.10.6 ([4ee2c71](https://github.com/galax-io/gatling-jdbc-plugin/commit/4ee2c718a78e82e3dc6a367ce37e31eaf5202e64))
## [0.18.1] - 2026-05-19

### Fixes
- Support gatling 3.12 ([608c37a](https://github.com/galax-io/gatling-jdbc-plugin/commit/608c37a735e5c347233feb1cfe68a71d0f89ef0a))
## [0.17.2] - 2026-05-17

### Documentation
- Fix compatibility table — suffix-based versioning ([fa93a04](https://github.com/galax-io/gatling-jdbc-plugin/commit/fa93a04dc5c6fe38a0c58268d147d8d132f53142))
## [0.17.1] - 2026-05-17

### Documentation
- Unified README with compatibility table, ToC, and 3-language quick start ([2efa227](https://github.com/galax-io/gatling-jdbc-plugin/commit/2efa227eba8875346c1a3e7a58b673ddb7a1ed8e))
## [0.17.0] - 2026-05-15

### Features
- Production-ready improvements — transactions, pool metrics, tests, CI ([c940098](https://github.com/galax-io/gatling-jdbc-plugin/commit/c940098db43ce7e4a50166dc6fd824980675ac93))
## [0.16.2] - 2026-05-15

### Fixes
- Preserve typed map values ([3500ac4](https://github.com/galax-io/gatling-jdbc-plugin/commit/3500ac4de916ba46b447f92820d086d8b9914b87))
## [0.16.1] - 2026-05-15

### Fixes
- Bound jdbc blocking threads ([f47e7c0](https://github.com/galax-io/gatling-jdbc-plugin/commit/f47e7c0686acf480a4cfbc867a700314bcfdb9a0))

### Documentation
- Restore kotlin dsl examples ([e386cb9](https://github.com/galax-io/gatling-jdbc-plugin/commit/e386cb992c1d25e7079b7ad377ab506adaa24f44))
## [0.16.0] - 2026-05-15

### Features
- Add jdbc result extractors ([f0e112c](https://github.com/galax-io/gatling-jdbc-plugin/commit/f0e112cd5262cfc6f99217dd4338d49728bff469))

### Documentation
- Add agent formatting policy ([54e27b8](https://github.com/galax-io/gatling-jdbc-plugin/commit/54e27b8305a219b801a9e858ca674a9ecba9f48e))

### Maintenance
- Apply scalafmt ([75dc475](https://github.com/galax-io/gatling-jdbc-plugin/commit/75dc4755f157d38c2ce42297cbec03ffe59dded5))
## [0.15.1] - 2026-05-15

### Features
- Document and test Gatling EL expression support in JDBC queries (#16) ([6d8668b](https://github.com/galax-io/gatling-jdbc-plugin/commit/6d8668b5f396b83cf291e9beedea1501e9db1bde))

### Fixes
- Mark session as failed when JDBC action returns KO status ([4a0c607](https://github.com/galax-io/gatling-jdbc-plugin/commit/4a0c607e2a7e926702b7cdc85a85bca2ab90a71b))

### Other
- Merge pull request #15 from galax-io/fix/mark-session-failed-on-ko ([eac509f](https://github.com/galax-io/gatling-jdbc-plugin/commit/eac509f691c3404e4815e324af4274e7893d7961))
## [0.15.0] - 2026-02-10

### Other
- Update plugins, dependencies, and build settings (#13) ([6d59398](https://github.com/galax-io/gatling-jdbc-plugin/commit/6d59398bd1e02dd5b8b343162792ea85ad70e8b1))
## [0.14.1] - 2025-08-18

### Fixes
- Typo in method name `insetInto` and deprecate its usage in favor of `insertInto` ([c5c480c](https://github.com/galax-io/gatling-jdbc-plugin/commit/c5c480cbc9391279fcacb1ce4a2e70c9fa8f763a))
## [0.14.0] - 2025-08-18

### Features
- Update workflows, dependencies, and build settings; ([f209bc8](https://github.com/galax-io/gatling-jdbc-plugin/commit/f209bc8d573849c9833e8dc5a05f8755599b4217))

### Maintenance
- Update build badge in README.md ([0bd63ee](https://github.com/galax-io/gatling-jdbc-plugin/commit/0bd63ee7fac293b4832012e640222b58b9132c64))
## [0.13.0] - 2024-12-15

### Maintenance
- Upgrade HikariCP 6.2.1 ([adafdba](https://github.com/galax-io/gatling-jdbc-plugin/commit/adafdba7797c3b786d4b7fb711cde5df13fc72ed))
- Upgrade h2 2.3.232 ([fa33455](https://github.com/galax-io/gatling-jdbc-plugin/commit/fa33455bd227debdaddb7847f69957a5f18050d7))
- Upgrade sbt 1.10.6 ([767f0b4](https://github.com/galax-io/gatling-jdbc-plugin/commit/767f0b482ea16a50fbfd1cb74d2a8e184fb468e6))
## [0.12.0] - 2024-06-26

### Features
- Update gatling 3.11.4 (#5) ([38cfc29](https://github.com/galax-io/gatling-jdbc-plugin/commit/38cfc29262aab879625b19e82b9e57d6c6774e5f))
## [0.11.0] - 2024-05-23

### Features
- Update gatling 3.10.5 (#4) ([d886f6f](https://github.com/galax-io/gatling-jdbc-plugin/commit/d886f6f6c77e1d02c55f457be258a2528d506e95))
## [0.10.3] - 2024-05-09

### Maintenance
- Initial Commit ([853337e](https://github.com/galax-io/gatling-jdbc-plugin/commit/853337e4bdd248c08f6405782da3434df9d7fcb3))
- Fix formatting ([12734d0](https://github.com/galax-io/gatling-jdbc-plugin/commit/12734d01755aec0203cb1042c141976a13a2669d))
- Initial commit ([f924926](https://github.com/galax-io/gatling-jdbc-plugin/commit/f924926761a022899874b15f64c6637e09c083a5))

### Other
- Update ci.yml ([f997006](https://github.com/galax-io/gatling-jdbc-plugin/commit/f997006e2c3a23f577675f2a611eab56a965ad5a))
- Support/mvn central (#3) ([93536c5](https://github.com/galax-io/gatling-jdbc-plugin/commit/93536c58f9fcd71167d77bbf9e7a4f19cb5c780d))

