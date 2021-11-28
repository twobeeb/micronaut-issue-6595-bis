package com.michelin.ns4kafka.cli;

import com.michelin.ns4kafka.cli.models.ApiResource;
import com.michelin.ns4kafka.cli.models.Resource;
import com.michelin.ns4kafka.cli.services.ApiResourcesService;
import com.michelin.ns4kafka.cli.services.FileService;
import com.michelin.ns4kafka.cli.services.LoginService;
import com.michelin.ns4kafka.cli.services.ResourceService;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Command(name = "apply", description = "Create or update a resource")
public class ApplySubcommand implements Runnable {

    @Inject
    public LoginService loginService;
    @Inject
    public ApiResourcesService apiResourcesService;
    @Inject
    public FileService fileService;
    @Inject
    public ResourceService resourceService;

    @Inject
    public KafkactlConfig kafkactlConfig;

    @CommandLine.ParentCommand
    public KafkactlCommand kafkactlCommand;
    @Option(names = {"-f", "--file"}, description = "YAML File or Directory containing YAML resources")
    public Optional<File> file;
    @Option(names = {"-R", "--recursive"}, description = "Enable recursive search of file")
    public boolean recursive;
    @Option(names = {"--dry-run"}, description = "Does not persist resources. Validate only")
    public boolean dryRun;

    

    @Override
    public void run() {

        if (dryRun) {
            System.out.println("Dry run execution");
        }

        boolean authenticated = loginService.doAuthenticate();
        if (!authenticated) {
            
            throw new UnsupportedOperationException("Login failed");
        }

        // 0. Check STDIN and -f
        boolean hasStdin = false;
        try {
            hasStdin = System.in.available() > 0;
        } catch (IOException e) {
            e.printStackTrace();
        }
        // If we have none or both stdin and File set, we stop
        if (hasStdin == file.isPresent()) {
            throw new UnsupportedOperationException( "Required one of -f or stdin");
        }

        List<Resource> resources;

        if (file.isPresent()) {
            // 1. list all files to process
            List<File> yamlFiles = fileService.computeYamlFileList(file.get(), recursive);
            if (yamlFiles.isEmpty()) {
                throw new UnsupportedOperationException( "Could not find yaml/yml files in " + file.get().getName());
            }
            // 2 load each files
            resources = fileService.parseResourceListFromFiles(yamlFiles);
        } else {
            Scanner scanner = new Scanner(System.in);
            scanner.useDelimiter("\\Z");
            // 2 load STDIN
            resources = fileService.parseResourceListFromString(scanner.next());
        }

        // 3. validate resource types from resources
        List<Resource> invalidResources = apiResourcesService.validateResourceTypes(resources);
        if (!invalidResources.isEmpty()) {
            String invalid = String.join(", ", invalidResources.stream().map(Resource::getKind).distinct().collect(Collectors.toList()));
            throw new UnsupportedOperationException( "The server doesn't have resource type [" + invalid + "]");
        }
        // 4. validate namespace mismatch
        String namespace = kafkactlCommand.optionalNamespace.orElse(kafkactlConfig.getCurrentNamespace());
        List<Resource> nsMismatch = resources.stream()
                .filter(resource -> resource.getMetadata().getNamespace() != null && !resource.getMetadata().getNamespace().equals(namespace))
                .collect(Collectors.toList());
        if (!nsMismatch.isEmpty()) {
            String invalid = String.join(", ", nsMismatch.stream().map(resource -> resource.getKind() + "/" + resource.getMetadata().getName()).distinct().collect(Collectors.toList()));
            throw new UnsupportedOperationException( "Namespace mismatch between kafkactl and yaml document [" + invalid + "]");
        }
        List<ApiResource> apiResources = apiResourcesService.getListResourceDefinition();

        // 5. process each document individually, return 0 when all succeed
        int errors = resources.stream()
                .map(resource -> {
                    ApiResource apiResource = apiResources.stream()
                            .filter(apiRes -> apiRes.getKind().equals(resource.getKind()))
                            .findFirst()
                            .orElseThrow(); // already validated
                    HttpResponse<Resource> response = resourceService.apply(apiResource, namespace, resource, dryRun);
                    if (response == null) {
                        return null;
                    }
                    Resource merged = response.body();
                    String resourceState = "";
                    if (response.header("X-Ns4kafka-Result") != null) {
                        resourceState = " (" +response.header("X-Ns4kafka-Result") + ")";
                    }
                    System.out.println(CommandLine.Help.Ansi.AUTO.string("@|bold,green Success |@") + merged.getKind() + "/" + merged.getMetadata().getName() + resourceState);

                    return merged;
                })
                .mapToInt(value -> value != null ? 0 : 1)
                .sum();

    }

}