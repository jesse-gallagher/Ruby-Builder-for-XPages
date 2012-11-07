Ruby Builder for XPages
=======================

To use:

1. Install the feature in Designer (I went through ugly process of exporting the feature project to the filesystem, then importing it into an Update Site NSF, and then installing in Designer from there)
2. Right-click the app to use Ruby and choose "Add/Remove Ruby Nature"
3. In package explorer, create two folders in the database: WebContent/WEB-INF/ruby-src and WebContent/WEB-INF/ruby-java
4. Add the ruby-java folder to your build path
5. Create .rb files containing classes in the ruby-src folder. It's not required to store them in folder trees to match their packages, but it's probably a good idea for cleanliness
6. Use JRuby-specific annotations in the Ruby class to define the Java package, implemented interfaces, and Java method names. See http://frostillic.us/f.nsf/posts/the-ruby-builder-for-xpages for an example