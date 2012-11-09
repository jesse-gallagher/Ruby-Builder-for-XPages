package frostillicus.ruby.builder;

import java.io.*;
import java.util.*;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import org.jruby.embed.*;

public class RubyBuilder extends IncrementalProjectBuilder {
	public static final String BUILDER_ID = "frostillicus.ruby.builder.rubyBuilder";
	private static final String MARKER_TYPE = "frostillicus.ruby.builder.rubyProblem";


	private final String SOURCE_DIR = "WebContent/WEB-INF/ruby-src";
	private final String BUILD_DIR = "WebContent/WEB-INF/ruby-java";

	private IFolder rubyBuild;

	void processRubyFile(IResource resource) throws CoreException {
		if(resource instanceof IFile && resource.getName().endsWith(".rb") && resource.getProjectRelativePath().toString().startsWith(SOURCE_DIR)) {
			try {
				IFile rubyFile = (IFile)resource;
				System.out.println("Added/Modified Ruby file " + rubyFile.getProjectRelativePath());

				// Make sure the output directory exists
				IProject project = resource.getProject();
				rubyBuild = project.getFolder(BUILD_DIR);
				if(!rubyBuild.exists()) {
					rubyBuild.create(false, false, null);
				}

				// Read the Ruby code
				StringBuilder rubyCode = new StringBuilder();
				BufferedReader reader = new BufferedReader(new InputStreamReader(rubyFile.getContents()));
				while(reader.ready()) { rubyCode.append(reader.readLine()); rubyCode.append('\n'); }
				reader.close();

				String filePath = rubyFile.getProjectRelativePath().toString().substring(SOURCE_DIR.length()+1);

				List<Map<String, String>> classes = compileScript(rubyCode.toString(), filePath);
				for(Map<String, String> classInfo : classes) {
					String packagePath = classInfo.get("package").replace('.', '/');
					if(packagePath.length() > 0) {
						createPackageFolders(packagePath);
					}
					String classFileName = classInfo.get("name") + ".java";


					IFile workspaceBuildFile = rubyBuild.getFile(packagePath + "/" + classFileName);
					ByteArrayInputStream bytes = new ByteArrayInputStream(classInfo.get("source").getBytes());

					if(!workspaceBuildFile.exists()) {
						workspaceBuildFile.create(bytes, 0, null);
					} else {
						workspaceBuildFile.setContents(bytes, 0, null);
					}

					System.out.println("Created/Modified file " + packagePath + "/" + classFileName);
				}

				System.out.println("Launched and terminated container");

				deleteMarkers(rubyFile);
			} catch(Exception e) {
				e.printStackTrace();
			}
		}
	}

	@SuppressWarnings("unused")
	private void addMarker(IFile file, String message, int lineNumber, int severity) {
		try {
			IMarker marker = file.createMarker(MARKER_TYPE);
			marker.setAttribute(IMarker.MESSAGE, message);
			marker.setAttribute(IMarker.SEVERITY, severity);
			if (lineNumber == -1) {
				lineNumber = 1;
			}
			marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
		} catch (CoreException e) { }
	}

	private void createPackageFolders(String packageName) throws Exception {
		String[] bits = packageName.split("/");
		String base = "";
		for(String bit : bits) {
			IFolder folder = rubyBuild.getFolder(base + bit);
			if(!folder.exists()) {
				System.out.println("creating folder " + folder.getProjectRelativePath().toString());
				folder.create(false, false, null);
			}
			base += "/" + bit;
		}
	}

	@SuppressWarnings({ "unchecked", "restriction" })
	private List<Map<String, String>> compileScript(String source, String filename) {

		// Create the script container and pass in a couple global variables to use
		ScriptingContainer container = new ScriptingContainer(LocalContextScope.THREADSAFE);
		container.put("$script_source", source);
		container.put("$script_filename", filename);
		container.setWriter(new PrintWriter(System.out));


		// Prepare for severe ugliness - Java writing Ruby writing Java
		container.runScriptlet(
				"def static_init(cls, script)\n" +
				"	return \"\\n\" +\n" +
				"	\"static {\\n\" +\n" +
				"	\"java.util.Map applicationScope = javax.faces.context.FacesContext.getCurrentInstance().getExternalContext().getApplicationMap();\\n\" +\n" +
				"	\"if(applicationScope.containsKey(\\\"__RubyRuntime\\\")) {\\n\" +\n" +
				"	\"	__ruby__ = (Ruby)applicationScope.get(\\\"__RubyRuntime\\\");\\n\" +\n" +
				"	\"} else {\\n\" +\n" +
				"	\"	Ruby globalRuby = Ruby.getGlobalRuntime();\\n\" +\n" +
				"	\"	org.jruby.RubyInstanceConfig config = globalRuby.getInstanceConfig();\\n\" +\n" +
				"	\"	config.setLoader(javax.faces.context.FacesContext.getCurrentInstance().getContextClassLoader());\\n\" +\n" +
				"	\"	__ruby__ = Ruby.newInstance(config);\\n\" +\n" +
				"	\"	applicationScope.put(\\\"__RubyRuntime\\\", __ruby__);\\n\" +\n" +
				"	\"}\\n\" +\n" +
				"	\" \\n\" +\n" +
				"		\"	#{requires_string(cls, script)}\\n\" +\n" +
				"	\"	RubyClass metaclass = __ruby__.getClass(\\\"#{cls.name}\\\");\\n\" +\n" +
				"	\"	metaclass.setRubyStaticAllocator(#{cls.name}.class);\\n\" +\n" +
				"	\"	if (metaclass == null) throw new NoClassDefFoundError(\\\"Could not load Ruby class: #{cls.name}\\\");\\n\" +\n" +
				"	\"	__metaclass__ = metaclass;\\n\" +\n" +
				"	\"}\"\n" +
				"end\n"
		);
		container.runScriptlet(
				"def requires_string(cls, source)\n" +
				"	if cls.requires.size == 0\n" +
				"		source_chunks = source.unpack(\"a32000\" * (source.size / 32000 + 1))\n" +
				"		source_chunks.each do |chunk|\n" +
				"			chunk.gsub!(/([\\\\\"])/, '\\\\\\\\\\1')\n" +
				"			chunk.gsub!(\"\\n\", \"\\\\n\\\" +\\n            \\\"\")\n" +
				"		end\n" +
				"		source_line = source_chunks.join(\"\\\")\\n          .append(\\\"\");\n" +
				"\n" +
				"		\"        String source = new StringBuilder(\\\"#{source_line}\\\").toString();\\n        __ruby__.executeScript(source, \\\"#{cls.script_name}\\\");\"\n" +
				"	else\n" +
				"		cls.requires.map do |r|\n" +
				"			\"        __ruby__.getLoadService().lockAndRequire(\\\"#{r}\\\");\"\n" +
				"		end.join(\"\\n\")\n" +
				"	end\n" +
				"end"
		);

		return (List<Map<String, String>>)container.runScriptlet(
				"require 'java'\n" +
				"require 'jruby/jrubyc'\n" +
				"runtime = JRuby.runtime\n" +
				"node = runtime.parse_file(java.io.ByteArrayInputStream.new($script_source.to_java_bytes), $script_filename, nil)\n" +
				"ruby_script = JRuby::Compiler::JavaGenerator.generate_java(node, $script_filename)\n" +

				"list = java.util.Vector.new\n" +
				"			ruby_script.classes.each do |cls|\n" +
				"				map = java.util.HashMap.new\n" +
				"				map['name'] = cls.name\n" +
				"				map['package'] = cls.package\n" +
				"				map['source'] = <<JAVA\n" +
				"			#{cls.package_string}\n" +
				"\n" +
				"			#{cls.imports_string}\n" +
				"\n" +
				"			#{cls.annotations_string}\n" +
				"			public class #{cls.name} extends RubyObject #{cls.interface_string} {\n" +
				"			    private static final Ruby __ruby__;\n" +
				"			    private static final RubyClass __metaclass__;\n" +
				"\n" +
				"			#{static_init(cls, $script_source)}\n" +
				"			#{cls.constructor_string}\n" +
				"			#{cls.methods_string}\n" +
				"			}\n" +
				"JAVA\n" +
				"				list.add(map)\n" +
				"			end\n" +
				"			list"
		);
	}

	class RubyDeltaVisitor implements IResourceDeltaVisitor {
		public boolean visit(IResourceDelta delta) throws CoreException {
			IResource resource = delta.getResource();
			switch (delta.getKind()) {
			case IResourceDelta.ADDED:
				// handle added resource
				processRubyFile(resource);
				break;
			case IResourceDelta.REMOVED:
				// handle removed resource
				break;
			case IResourceDelta.CHANGED:
				// handle changed resource
				processRubyFile(resource);
				break;
			}
			//return true to continue visiting children.
			return true;
		}
	}

	class RubyResourceVisitor implements IResourceVisitor {
		public boolean visit(IResource resource) {
			try {
				processRubyFile(resource);
			} catch(CoreException e) {
				e.printStackTrace();
			}
			//return true to continue visiting children.
			return true;
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
		if (kind == FULL_BUILD) {
			fullBuild(monitor);
		} else {
			IResourceDelta delta = getDelta(getProject());
			if (delta == null) {
				fullBuild(monitor);
			} else {
				incrementalBuild(delta, monitor);
			}
		}
		return null;
	}

	private void deleteMarkers(IFile file) {
		try {
			file.deleteMarkers(MARKER_TYPE, false, IResource.DEPTH_ZERO);
		} catch (CoreException ce) { }
	}

	protected void fullBuild(final IProgressMonitor monitor) throws CoreException {
		try {
			getProject().accept(new RubyResourceVisitor());
		} catch (CoreException e) { }
	}

	protected void incrementalBuild(IResourceDelta delta, IProgressMonitor monitor) throws CoreException {
		delta.accept(new RubyDeltaVisitor());
	}
}
