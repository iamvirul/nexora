import os
import xml.etree.ElementTree as ET

def remove_dependency_versions(pom_path):
    ET.register_namespace('', 'http://maven.apache.org/POM/4.0.0')
    tree = ET.parse(pom_path)
    root = tree.getroot()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    
    changed = False
    
    # Remove version from dependencies
    dependencies = root.find('m:dependencies', ns)
    if dependencies is not None:
        for dep in dependencies.findall('m:dependency', ns):
            version = dep.find('m:version', ns)
            if version is not None:
                dep.remove(version)
                changed = True
                
    # Also remove <properties><picocli.version> from nexora-cli
    properties = root.find('m:properties', ns)
    if properties is not None:
        for prop in list(properties):
            if prop.tag == '{http://maven.apache.org/POM/4.0.0}picocli.version':
                properties.remove(prop)
                changed = True

    if changed:
        tree.write(pom_path, encoding='utf-8', xml_declaration=True)
        print(f"Updated {pom_path}")

for root_dir, dirs, files in os.walk('.'):
    for f in files:
        if f == 'pom.xml' and root_dir != '.':
            remove_dependency_versions(os.path.join(root_dir, f))
