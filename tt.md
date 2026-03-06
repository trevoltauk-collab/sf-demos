To answer your question directly: Technically, no, flying-saucer-pdf-openpdf does not use the "iText" brand library you see today. Instead, it uses OpenPDF, which is a fork of an older version of iText.
The confusion usually arises because OpenPDF still uses the com.lowagie package name (named after iText's creator), which makes the code look like iText in your imports.
The Key Distinction
While the underlying logic is derived from iText, the two libraries serve different licensing and versioning needs:
 * OpenPDF: A community-maintained fork of iText 2.1.7. It was created to keep a version of the library available under the LGPL and MPL licenses after iText switched to the more restrictive AGPL license (starting with version 5.0).
 * iText (Modern): Refers to versions 5, 7, or 8, which require a commercial license for closed-source projects.
Dependency Breakdown for 9.3.4
The artifact org.xhtmlrenderer:flying-saucer-pdf-openpdf:9.3.4 is specifically designed to swap out the old iText dependency for OpenPDF.
| Feature | flying-saucer-pdf (Standard) | flying-saucer-pdf-openpdf |
|---|---|---|
| Primary Engine | iText 2.1.7 (Older) | OpenPDF 1.3.x |
| License | LGPL | LGPL / MPL |
| Package Names | com.lowagie.text.* | com.lowagie.text.* (Same as iText 2) |
| Maintenance | Minimal | Active |
Why this version exists
Flying Saucer created this specific openpdf artifact to help developers avoid the "unmaintained" nature of the original iText 2.1.7 while staying within the same "friendlier" open-source licensing.
> Note: In very recent versions of Flying Saucer (9.5.0+), the project has consolidated. The artifact flying-saucer-pdf-openpdf was actually deprecated and merged into the main flying-saucer-pdf artifact, which now uses OpenPDF by default.
> 
Would you like me to help you with the Maven or Gradle configuration to ensure you're using the correct version for your project?



If your goal is to move away from iText’s restrictive AGPL license (the "pay or go open-source" license), then using Flying Saucer with OpenPDF is exactly the right move.
You likely do not need to include it in your migration list as a "problem" library. In fact, flying-saucer-pdf-openpdf is the solution most developers use to escape iText's commercial licensing while keeping their PDF generation code mostly the same.
Why you are likely safe
To confirm you are clear of iText's commercial constraints, here is how the licensing breaks down:
 * iText 2.1.7 (and earlier): Licensed under LGPL/MPL. This is "safe" for most commercial use.
 * OpenPDF (what you use): This is a fork of that "safe" iText 2.1.7. It is also licensed under LGPL and MPL.
 * iText 5 / 7 / 8: Licensed under AGPL. This is the version that causes licensing headaches and requires a paid fee for closed-source products.
Comparison of Your Current Stack vs. iText
Since you are using flying-saucer-pdf-openpdf:9.3.4, your dependency tree looks like this:
| Dependency | License | Relationship to iText |
|---|---|---|
| Flying Saucer | LGPL | The "wrapper" that converts HTML/CSS to PDF. |
| OpenPDF | LGPL / MPL | The engine. It is a fork, not a "distribution" of modern iText. |
| iText 5+ | AGPL | The library you are trying to avoid. |
Does your code need to change?
If your code only interacts with Flying Saucer (e.g., ITextRenderer), you are in the clear. However, check your imports for these two scenarios:
 * Direct com.lowagie imports: These are part of OpenPDF. They look like old iText, but they are covered under the LGPL license. You can keep these.
 * Direct com.itextpdf imports: If you see this package name anywhere in your project, that is the modern iText. That specific code must be rewritten to use OpenPDF equivalent classes.
Recommendation
You should keep Flying Saucer OpenPDF. It was specifically built to act as a drop-in replacement for the iText version of Flying Saucer to solve the exact licensing hurdle you are facing.
Would you like me to show you a Maven/Gradle command to scan your project for any hidden com.itextpdf dependencies that might still be lingering?


To identify if any "license-restricted" versions of iText are still lurking in your project, you can use your build tool's dependency tree command. This is the most reliable way to see if another library is secretly pulling in a commercial version of iText.
1. Maven Dependency Scan
Run the following command in your terminal at the root of your project:
mvn dependency:tree -Dincludes=com.itextpdf:*,com.lowagie:*

 * com.lowagie: This is safe. It represents OpenPDF or the old LGPL iText.
 * com.itextpdf: This is unsafe. If this appears, it means a version of iText 5 or newer is in your classpath, and you are subject to the AGPL license.
2. Gradle Dependency Scan
If you use Gradle, run this command:
./gradlew dependencies | grep -E "com.itextpdf|com.lowagie"

Understanding the "Package" Trap
One thing that trips up many teams during a migration is that OpenPDF intentionally kept the old iText package names (com.lowagie.*) to make it a "drop-in" replacement.
| If your code imports... | It belongs to... | License Status |
|---|---|---|
| com.lowagie.text.* | OpenPDF | Safe (LGPL/MPL) |
| com.itextpdf.text.* | iText 5.x | Unsafe (AGPL) |
| com.itextpdf.kernel.* | iText 7.x / 8.x | Unsafe (AGPL) |
Your Migration Checklist
Since you are already using flying-saucer-pdf-openpdf, you have done 90% of the work. To finish the migration:
 * Remove any explicit dependencies on com.itextpdf:itextpdf (v5) or com.itextpdf:kernel (v7+) from your pom.xml or build.gradle.
 * Global Search: Search your entire codebase for the string import com.itextpdf. If zero results come up, your source code is clean.
 * Transitive Check: Use the commands above to ensure a third-party reporting tool or library isn't bringing iText back in through the "back door."
Would you like me to help you write a Maven "exclusion" rule to block any commercial iText versions from accidentally entering your project again?



To ensure no commercial iText versions accidentally "sneak" back into your project through other dependencies (transitive dependencies), you can use an Exclusion Rule in Maven or a Capability/Substitution in Gradle.
This is a "set it and forget it" safety net for your migration.
1. Maven: The Global Exclusion
The cleanest way to handle this in Maven is to add an exclusion to any library that might be pulling in the old iText. However, if you want to be aggressive, you can use the Maven Enforcer Plugin. This will actually fail your build if an AGPL version of iText is detected.
Add this to your pom.xml under <build><plugins>:
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>3.4.1</version>
    <executions>
        <execution>
            <id>enforce-no-itext-agpl</id>
            <goals>
                <goal>enforce</goal>
            </goals>
            <configuration>
                <rules>
                    <bannedDependencies>
                        <excludes>
                            <exclude>com.itextpdf:itextpdf</exclude>
                            <exclude>com.itextpdf:itext7-core</exclude>
                            <exclude>com.itextpdf:*</exclude>
                        </excludes>
                        <message>Stop! Commercial iText (AGPL) detected. Use OpenPDF instead.</message>
                    </bannedDependencies>
                </rules>
                <fail>true</fail>
            </configuration>
        </execution>
    </executions>
</plugin>

2. Gradle: Dependency Constraints
In Gradle, you can use constraints or exclude rules. A very effective way to handle this globally is to tell Gradle to exclude iText from all configurations:
configurations.all {
    exclude group: 'com.itextpdf', module: 'itextpdf'
    exclude group: 'com.itextpdf', module: 'itext7-core'
}

Verifying Your Victory
Once you apply these changes, run your build.
 * If it passes: You are 100% clean of the restricted iText library.
 * If it fails: The error message will tell exactly which "innocent" library in your project was trying to pull in the paid version of iText.
Final Migration Note
Since you are on 9.3.4, keep in mind that Flying Saucer (OpenPDF version) is a very stable "maintenance" branch. It won't have the cutting-edge features of iText 8 (like advanced PDF/UA or complex digital signatures), but for standard HTML-to-PDF conversion, it is the industry standard for avoiding licensing fees.
Would you like me to review a snippet of your pom.xml or build.gradle to make sure the exclusions are placed correctly?
