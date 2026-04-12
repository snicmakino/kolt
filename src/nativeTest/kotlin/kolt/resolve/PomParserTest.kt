package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PomParserTest {

    @Test
    fun parseMinimalPomWithSingleDependency() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-stdlib</artifactId>
                        <version>2.1.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals("com.example", pom.groupId)
        assertEquals("mylib", pom.artifactId)
        assertEquals("1.0.0", pom.version)
        assertNull(pom.parent)
        assertEquals(1, pom.dependencies.size)

        val dep = pom.dependencies[0]
        assertEquals("org.jetbrains.kotlin", dep.groupId)
        assertEquals("kotlin-stdlib", dep.artifactId)
        assertEquals("2.1.0", dep.version)
        assertEquals(null, dep.scope)
        assertEquals(false, dep.optional)
    }

    @Test
    fun parseMultipleDependenciesWithScopeAndOptional() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                        <version>2.0.9</version>
                    </dependency>
                    <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                        <scope>test</scope>
                    </dependency>
                    <dependency>
                        <groupId>com.google.code.findbugs</groupId>
                        <artifactId>jsr305</artifactId>
                        <version>3.0.2</version>
                        <optional>true</optional>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals(3, pom.dependencies.size)

        assertEquals("test", pom.dependencies[1].scope)
        assertEquals(true, pom.dependencies[2].optional)
    }

    @Test
    fun parsePomWithProperties() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <properties>
                    <kotlin.version>2.1.0</kotlin.version>
                    <slf4j.version>2.0.9</slf4j.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-stdlib</artifactId>
                        <version>${'$'}{kotlin.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals(2, pom.properties.size)
        assertEquals("2.1.0", pom.properties["kotlin.version"])
        assertEquals("2.0.9", pom.properties["slf4j.version"])
        assertEquals("2.1.0", pom.dependencies[0].version)
    }

    @Test
    fun parsePomWithDependencyManagement() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.9</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals(1, pom.dependencyManagement.size)
        assertEquals("2.0.9", pom.dependencyManagement[0].version)
        assertNull(pom.dependencies[0].version)
    }

    @Test
    fun parsePomWithParent() {
        val xml = """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>2.0.0</version>
                </parent>
                <artifactId>child</artifactId>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        val parent = assertNotNull(pom.parent)
        assertEquals("com.example", parent.groupId)
        assertEquals("parent-pom", parent.artifactId)
        assertEquals("2.0.0", parent.version)
        assertNull(pom.groupId)
        assertEquals("child", pom.artifactId)
        assertNull(pom.version)
    }

    @Test
    fun parsePomWithXmlComments() {
        val xml = """
            <project>
                <!-- This is a comment -->
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <!-- dependencies section -->
                <dependencies>
                    <!-- a dep -->
                    <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals(1, pom.dependencies.size)
    }

    @Test
    fun parsePomWithProjectVersionProperty() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>3.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>sub-module</artifactId>
                        <version>${'$'}{project.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals("3.0.0", pom.dependencies[0].version)
    }

    @Test
    fun parsePomWithNoDependencies() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals(0, pom.dependencies.size)
        assertEquals(0, pom.dependencyManagement.size)
    }

    @Test
    fun parsePomWithSelfClosingTags() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <dependencies/>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals(0, pom.dependencies.size)
    }

    @Test
    fun parsePomWithGroupIdVersionProperty() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>${'$'}{project.groupId}</groupId>
                        <artifactId>other</artifactId>
                        <version>${'$'}{project.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertEquals("com.example", pom.dependencies[0].groupId)
        assertEquals("1.0.0", pom.dependencies[0].version)
    }

    @Test
    fun parsePomWithExclusions() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib</artifactId>
                        <version>2.0.0</version>
                        <exclusions>
                            <exclusion>
                                <groupId>org.unwanted</groupId>
                                <artifactId>bad-lib</artifactId>
                            </exclusion>
                            <exclusion>
                                <groupId>org.also-unwanted</groupId>
                                <artifactId>another</artifactId>
                            </exclusion>
                        </exclusions>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        val dep = pom.dependencies[0]
        assertEquals(2, dep.exclusions.size)
        assertEquals("org.unwanted", dep.exclusions[0].groupId)
        assertEquals("bad-lib", dep.exclusions[0].artifactId)
        assertEquals("org.also-unwanted", dep.exclusions[1].groupId)
        assertEquals("another", dep.exclusions[1].artifactId)
    }

    @Test
    fun parsePomWithWildcardExclusion() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib</artifactId>
                        <version>1.0.0</version>
                        <exclusions>
                            <exclusion>
                                <groupId>*</groupId>
                                <artifactId>*</artifactId>
                            </exclusion>
                        </exclusions>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        val dep = pom.dependencies[0]
        assertEquals(1, dep.exclusions.size)
        assertEquals("*", dep.exclusions[0].groupId)
        assertEquals("*", dep.exclusions[0].artifactId)
    }

    @Test
    fun parsePomWithNoExclusionsDefaultsToEmpty() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>mylib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = assertNotNull(parsePom(xml).get())
        assertTrue(pom.dependencies[0].exclusions.isEmpty())
    }
}
