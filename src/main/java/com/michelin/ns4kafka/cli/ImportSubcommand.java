package com.michelin.ns4kafka.cli;

import com.michelin.ns4kafka.cli.models.ApiResource;
import com.michelin.ns4kafka.cli.models.Resource;
import com.michelin.ns4kafka.cli.services.ApiResourcesService;
import com.michelin.ns4kafka.cli.services.FormatService;
import com.michelin.ns4kafka.cli.services.LoginService;
import com.michelin.ns4kafka.cli.services.ResourceService;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "import", description = "Import resources already present on the Kafka Cluster in ns4kafka")
public class ImportSubcommand implements Runnable {

    @Inject
    public LoginService loginService;
    @Inject
    public ResourceService resourceService;
    @Inject
    public ApiResourcesService apiResourcesService;
    @Inject
    public FormatService formatService;
    @Inject
    public KafkactlConfig kafkactlConfig;

    @CommandLine.ParentCommand
    public KafkactlCommand kafkactlCommand;
    @Parameters(index = "0", description = "Resource type", arity = "1")
    public String resourceType;
    @Option(names = {"--dry-run"}, description = "Does not persist resources. Validate only")
    public boolean dryRun;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    public void run() {
        
        if (dryRun) {
            System.out.println("Dry run execution");
        }
        // 1. Authent
        boolean authenticated = loginService.doAuthenticate();
        if (!authenticated) {
            throw new UnsupportedOperationException( "Login failed");
        }

        // 2. validate resourceType + custom type ALL
        List<ApiResource> apiResources = validateResourceType();

        String namespace = kafkactlCommand.optionalNamespace.orElse(kafkactlConfig.getCurrentNamespace());

        Map<ApiResource, List<Resource>> resources = resourceService.importAll(apiResources, namespace, dryRun);

        // 5.a display all resources by type
        resources.forEach((k, v) -> formatService.displayList(k.getKind(), v, "table"));

    }

    private List<ApiResource> validateResourceType() {
        // specific case ALL
        if (resourceType.equalsIgnoreCase("ALL")) {
            return apiResourcesService.getListResourceDefinition()
                    .stream()
                    .filter(apiResource -> apiResource.isSynchronizable())
                    .collect(Collectors.toList());
        }
        // otherwise check resource exists
        Optional<ApiResource> optionalApiResource = apiResourcesService.getResourceDefinitionFromCommandName(resourceType);
        if (optionalApiResource.isEmpty()) {
            throw new UnsupportedOperationException( "The server doesn't have resource type " + resourceType);
        }
        if(!optionalApiResource.get().isSynchronizable()){
            throw new UnsupportedOperationException( "Resource Type " + resourceType+" is not synchronizable");
        }

        return List.of(optionalApiResource.get());
    }
}